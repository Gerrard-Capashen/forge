package forge.mtga.draft;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.DraftPack;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * One draft table: Forge's {@link TableDraft} with some seats played by people and the rest by Forge's draft AI.
 * Modelled on Forge's network draft host ({@code forge.gamemodes.net.draft.BoosterDraftHost}), but it reports to
 * {@link Seat} callbacks instead of Forge's lobby server, so the same table can run for one local player or, later,
 * for seats on other machines.
 * <p>
 * Every seat has its own pack queue: a pick passes the pack on at once, whatever the others are doing, so a fast
 * picker can have several packs waiting. A person gets the pack at the head of their queue with a pick timer; when it
 * runs out the reserved card (or the first card) is taken for them. Only cards {@code shown} accepts are offered to a
 * person (the client cannot draw the others); a pack with none of them is picked for them at once.
 * <p>
 * Mutable state is guarded by {@code this}; callbacks run outside the lock.
 */
public final class DraftTable {
    /** What a person's seat is told. Calls come from the table's threads, never under its lock. */
    public interface Seat {
        /** A pack is at the head of this seat's queue: pick from {@code cards} (1-based pack and pick) within {@code seconds}. */
        void pack(DraftTable table, List<PaperCard> cards, int packNo, int pickNo, int seconds);

        /** A card was taken for this seat (timer ran out, or nothing in the pack could be shown). */
        default void autoPicked(DraftTable table, PaperCard card) { }

        /** Some seat picked: the number of packs waiting per seat changed. */
        default void moved(DraftTable table) { }

        /** This seat has made its last pick: {@code pool} is everything it drafted. */
        void done(DraftTable table, List<PaperCard> pool);

        /** Every seat has finished: the bots' decks are built ({@link #botDecks()}). */
        default void tableOver(DraftTable table) { }
    }

    private final String id = UUID.randomUUID().toString();
    private final TableDraft draft;
    private final Map<Integer, Seat> people;
    private final Predicate<PaperCard> shown;
    private final int podSize;
    private final List<List<PaperCard>> picks = new ArrayList<>();
    private final int[] picksThisRound;
    private final boolean[] inFlight, finished;
    private final PaperCard[] reserved;
    /** Bot seats (and away people) with a pick scheduled (see {@link #setBotPace}). */
    private final boolean[] botPending;
    /** People who are gone for now: their seat picks like a bot until they are back ({@link #setAway}). */
    private final boolean[] away;
    /** Seconds a seat's pick timer runs longer than it is told (relay latency of a remote seat). */
    private final int[] grace;
    private final java.util.Random rnd = new java.util.Random();
    private int botMinMs, botMaxMs;
    private final Map<Integer, ScheduledFuture<?>> timers = new HashMap<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-table-timer");
        t.setDaemon(true);
        return t;
    });
    private int round, packSize, lastRound;
    private volatile boolean over;
    private Deck[] botDecks;

    /**
     * @param people seat index -> callbacks of the person in it; every other seat is a bot
     * @param shown  cards a person may be offered
     */
    public DraftTable(TableDraft draft, Map<Integer, Seat> people, Predicate<PaperCard> shown) {
        this.draft = draft;
        this.people = Map.copyOf(people);
        this.shown = shown;
        draft.setHumanSeats(people.keySet());
        draft.initializeBoosters();
        this.podSize = draft.getAllPlayers().size();
        for (int i = 0; i < podSize; i++) picks.add(new ArrayList<>());
        this.picksThisRound = new int[podSize];
        this.inFlight = new boolean[podSize];
        this.finished = new boolean[podSize];
        this.reserved = new PaperCard[podSize];
        this.botPending = new boolean[podSize];
        this.away = new boolean[podSize];
        this.grace = new int[podSize];
        this.round = draft.getRound();
        this.lastRound = draft.getNumRounds();
        this.packSize = headSize();
    }

    public String id() { return id; }

    public int podSize() { return podSize; }

    public boolean isPerson(int seat) { return people.containsKey(seat); }

    /** Packs pass to seat+1 in odd rounds, seat-1 in even ones. */
    public boolean passesUp() { return round % 2 == 1; }

    public boolean isOver() { return over; }

    /**
     * Bots take {@code minMs..maxMs} per pick instead of picking at once, so packs wait at their seats as they would with
     * people (the table view and the neighbours' pack counts then show them moving). 0 = instant (default).
     */
    public synchronized void setBotPace(int minMs, int maxMs) {
        botMinMs = Math.max(0, minMs);
        botMaxMs = Math.max(botMinMs, maxMs);
    }

    /** The seat's pick timer runs {@code seconds} longer than the time it is told (a seat on another machine). */
    public synchronized void setGrace(int seat, int seconds) {
        if (seat >= 0 && seat < podSize) grace[seat] = Math.max(0, seconds);
    }

    /**
     * A person left ({@code true}): their seat picks like a bot (at the bots' pace) until they are back ({@code false}),
     * when the head of their queue is offered to them again with a fresh timer. Their picks so far stay theirs.
     */
    public void setAway(int seat, boolean gone) {
        List<Runnable> out = new ArrayList<>();
        synchronized (this) {
            if (over || !people.containsKey(seat) || finished[seat] || away[seat] == gone) return;
            away[seat] = gone;
            cancelTimer(seat);
            inFlight[seat] = false;
            reserved[seat] = null;
            System.out.println("[mtga] draft table: seat " + seat + (gone ? " is away, picking for it" : " is back"));
            advance(out);
        }
        run(out);
    }

    public synchronized boolean isAway(int seat) { return seat >= 0 && seat < podSize && away[seat]; }

    /** Deal the first packs. */
    public void start() {
        List<Runnable> out = new ArrayList<>();
        synchronized (this) {
            advance(out);
        }
        run(out);
    }

    /** Packs waiting per seat (the head included). */
    public synchronized int[] queueSizes() {
        int[] q = new int[podSize];
        for (int i = 0; i < podSize; i++) q[i] = draft.getAllPlayers().get(i).getPackQueueSize();
        return q;
    }

    /** The cards a person's seat has drafted so far. */
    public synchronized List<PaperCard> picks(int seat) { return new ArrayList<>(picks.get(seat)); }

    /** The pack at the head of a seat's queue as offered to a person, or an empty list. */
    public synchronized List<PaperCard> head(int seat) {
        DraftPack p = draft.getAllPlayers().get(seat).nextChoice();
        return p == null ? List.of() : offered(p);
    }

    /** 1-based pack and pick numbers of the seat's next pick. */
    public synchronized int[] position(int seat) { return new int[] {round, picksThisRound[seat] + 1}; }

    /** The seconds a seat gets for a pick from a pack of {@code cards} cards (Arena-like: shorter as the pack shrinks). */
    public static int pickSeconds(int cards) { return Math.max(10, Math.min(75, 5 * cards + 5)); }

    /**
     * A person picks {@code card} from the head of their queue.
     * @return false if that card is not in the pack offered to them (nothing changes)
     */
    public boolean pick(int seat, PaperCard card) {
        List<Runnable> out = new ArrayList<>();
        synchronized (this) {
            if (over || !people.containsKey(seat) || finished[seat]) return false;
            LimitedPlayer p = draft.getAllPlayers().get(seat);
            DraftPack head = p.nextChoice();
            if (head == null || !head.contains(card) || !shown.test(card)) return false;
            cancelTimer(seat);
            inFlight[seat] = false;
            reserved[seat] = null;
            take(p, seat, card, out);
            advance(out);
        }
        run(out);
        return true;
    }

    /** The card to take if this seat's timer runs out (null clears it). */
    public synchronized void reserve(int seat, PaperCard card) {
        if (seat >= 0 && seat < podSize) reserved[seat] = card;
    }

    /** Stop the table without finishing it (everyone left). */
    public synchronized void close() {
        over = true;
        for (Integer s : new ArrayList<>(timers.keySet())) cancelTimer(s);
        clock.shutdownNow();
    }

    /** The bots' built decks, once the table is over (empty before). */
    public synchronized List<Deck> botDecks() { return botDecks == null ? List.of() : List.of(botDecks); }

    // ---- inside the lock --------------------------------------------------------------------------------------------

    private List<PaperCard> offered(DraftPack p) {
        List<PaperCard> out = new ArrayList<>();
        for (PaperCard c : p) if (shown.test(c)) out.add(c);
        return out;
    }

    private int headSize() {
        for (LimitedPlayer pl : draft.getAllPlayers()) {
            DraftPack p = pl.nextChoice();
            if (p != null && !p.isEmpty()) return p.size();
        }
        return 0;
    }

    /** Draft {@code card} for the seat and pass the pack on, unless the seat keeps it for another pick. */
    private void take(LimitedPlayer p, int seat, PaperCard card, List<Runnable> out) {
        Boolean pass = p.draftCard(card, DeckSection.Sideboard);
        picks.get(seat).add(card);
        picksThisRound[seat]++;
        DraftPack head = p.nextChoice();
        boolean keep = head != null && !head.isEmpty() && (Boolean.FALSE.equals(pass) || draft.keepsPackAfterPick(p));
        if (!keep) {
            DraftPack passed = p.passPack();
            if (passed != null && !passed.isEmpty()) {
                int next = Math.floorMod(seat + (passesUp() ? 1 : -1), podSize);
                draft.getAllPlayers().get(next).receiveOpenedPack(passed);
            }
        }
        if (people.containsKey(seat) && round == lastRound && picksThisRound[seat] >= packSize) {
            finished[seat] = true;
            Seat s = people.get(seat);
            List<PaperCard> pool = new ArrayList<>(picks.get(seat));
            out.add(() -> s.done(this, pool));
        }
        for (Seat s : people.values()) out.add(() -> s.moved(this));
    }

    /** Bots pick, empty-for-the-client packs are auto-picked, rounds advance, then people get their head packs. */
    private void advance(List<Runnable> out) {
        while (!over) {
            if (draft.isRoundOver()) {
                if (!draft.startRound()) {
                    finish(out);
                    return;
                }
                round = draft.getRound();
                java.util.Arrays.fill(picksThisRound, 0);
                packSize = headSize();
            }
            boolean progressed = false;
            List<LimitedPlayer> players = draft.getAllPlayers();
            for (int i = 0; i < podSize && !progressed; i++) {
                LimitedPlayer p = players.get(i);
                DraftPack head = p.nextChoice();
                if (head == null || head.isEmpty()) continue;
                boolean stands = p instanceof LimitedPlayerAI || away[i] && people.containsKey(i) && !finished[i];
                if (stands && botMaxMs > 0) {
                    if (!botPending[i]) {
                        botPending[i] = true;
                        int seat = i;
                        clock.schedule(() -> botPick(seat), botMinMs + rnd.nextInt(botMaxMs - botMinMs + 1), TimeUnit.MILLISECONDS);
                    }
                    continue;
                }
                if (p instanceof LimitedPlayerAI ai) {
                    if (p.shouldSkipThisPick()) {
                        DraftPack skip = p.passPack();
                        if (skip != null && !skip.isEmpty()) draft.getAllPlayers().get(Math.floorMod(i + (passesUp() ? 1 : -1), podSize)).receiveOpenedPack(skip);
                    } else {
                        PaperCard c = ai.chooseCard();
                        if (c == null) continue;
                        take(p, i, c, out);
                    }
                    progressed = true;
                } else if (stands) {
                    awayPick(p, i, head, out);
                    progressed = true;
                } else if (offered(head).isEmpty() && !inFlight[i]) {
                    PaperCard c = head.get(0);
                    take(p, i, c, out);
                    Seat s = people.get(i);
                    if (s != null) out.add(() -> s.autoPicked(this, c));
                    progressed = true;
                }
            }
            if (progressed) continue;
            for (Map.Entry<Integer, Seat> e : people.entrySet()) {
                int seat = e.getKey();
                if (inFlight[seat] || finished[seat] || away[seat]) continue;
                DraftPack head = players.get(seat).nextChoice();
                if (head == null || head.isEmpty()) continue;
                List<PaperCard> cards = offered(head);
                int packNo = round, pickNo = picksThisRound[seat] + 1, secs = pickSeconds(head.size());
                inFlight[seat] = true;
                startTimer(seat, secs + grace[seat]);
                Seat s = e.getValue();
                out.add(() -> s.pack(this, cards, packNo, pickNo, secs));
            }
            return;
        }
    }

    /** A paced bot's pick is due: it takes from its head pack, then the table moves on. */
    private void botPick(int seat) {
        List<Runnable> out = new ArrayList<>();
        synchronized (this) {
            botPending[seat] = false;
            if (over) return;
            LimitedPlayer p = draft.getAllPlayers().get(seat);
            DraftPack head = p.nextChoice();
            if (head != null && !head.isEmpty() && p instanceof LimitedPlayerAI ai) {
                if (p.shouldSkipThisPick()) {
                    DraftPack skip = p.passPack();
                    if (skip != null && !skip.isEmpty()) draft.getAllPlayers().get(Math.floorMod(seat + (passesUp() ? 1 : -1), podSize)).receiveOpenedPack(skip);
                } else {
                    PaperCard c = ai.chooseCard();
                    if (c != null) take(p, seat, c, out);
                }
            } else if (head != null && !head.isEmpty() && away[seat] && people.containsKey(seat) && !finished[seat]) {
                awayPick(p, seat, head, out);
            }
            advance(out);
        }
        run(out);
    }

    /** An away person's pick: what a bot with their picks so far would take (Forge's draft ranking). */
    private void awayPick(LimitedPlayer p, int seat, DraftPack head, List<Runnable> out) {
        List<PaperCard> cards = offered(head);
        PaperCard c;
        if (cards.isEmpty()) {
            c = head.get(0);
        } else {
            try {
                ColorSet colors = colorsOf(picks.get(seat));
                c = forge.gamemodes.limited.CardRanker.rankCardsInPack(cards, picks.get(seat), colors, colors.countColors() < 2).get(0);
            } catch (Exception e) {
                c = cards.get(0);
            }
        }
        take(p, seat, c, out);
        Seat s = people.get(seat);
        PaperCard picked = c;
        out.add(() -> s.autoPicked(this, picked));
    }

    /** The two colours a bot would have settled on with these picks (Forge's DeckColors rule, replayed). */
    private static ColorSet colorsOf(List<PaperCard> picks) {
        int mask = 0, count = 0;
        for (PaperCard pc : picks) {
            if (count >= 2) break;
            ColorSet add = ColorSet.fromMask(mask).inverse().getSharedColors(pc.getRules().getDeckbuildingColors());
            if (add.isColorless()) continue;
            for (byte color : MagicColor.WUBRG) {
                if (add.hasAnyColor(color)) {
                    mask |= color;
                    count++;
                }
                if (count >= 2) break;
            }
        }
        return ColorSet.fromMask(mask);
    }

    private void finish(List<Runnable> out) {
        over = true;
        draft.postDraftActions();
        List<Deck> decks = new ArrayList<>();
        for (int i = 0; i < podSize; i++) {
            LimitedPlayer p = draft.getAllPlayers().get(i);
            if (p instanceof LimitedPlayerAI ai) {
                try {
                    decks.add(ai.buildDeck(TableDraft.landSet()));
                } catch (Exception e) {
                    System.out.println("[mtga] draft table: bot " + i + " could not build a deck: " + e);
                }
            }
        }
        botDecks = decks.toArray(new Deck[0]);
        for (Map.Entry<Integer, Seat> e : people.entrySet()) {
            if (finished[e.getKey()]) continue;
            finished[e.getKey()] = true;
            List<PaperCard> pool = new ArrayList<>(picks.get(e.getKey()));
            Seat s = e.getValue();
            out.add(() -> s.done(this, pool));
        }
        for (Seat s : people.values()) out.add(() -> s.tableOver(this));
        for (Integer s : new ArrayList<>(timers.keySet())) cancelTimer(s);
        clock.shutdown();
        System.out.println("[mtga] draft table " + id + " over (" + decks.size() + " bot decks)");
    }

    private void startTimer(int seat, int seconds) {
        cancelTimer(seat);
        timers.put(seat, clock.schedule(() -> expired(seat), seconds, TimeUnit.SECONDS));
    }

    private void cancelTimer(int seat) {
        ScheduledFuture<?> f = timers.remove(seat);
        if (f != null) f.cancel(false);
    }

    private void expired(int seat) {
        List<Runnable> out = new ArrayList<>();
        synchronized (this) {
            if (over || !inFlight[seat]) return;
            LimitedPlayer p = draft.getAllPlayers().get(seat);
            DraftPack head = p.nextChoice();
            if (head == null || head.isEmpty()) return;
            List<PaperCard> cards = offered(head);
            PaperCard c = reserved[seat] != null && cards.contains(reserved[seat]) ? reserved[seat] : cards.isEmpty() ? head.get(0) : cards.get(0);
            reserved[seat] = null;
            timers.remove(seat);
            inFlight[seat] = false;
            take(p, seat, c, out);
            Seat s = people.get(seat);
            out.add(() -> s.autoPicked(this, c));
            advance(out);
        }
        run(out);
    }

    private static void run(List<Runnable> out) {
        for (Runnable r : out) {
            try {
                r.run();
            } catch (Exception e) {
                System.out.println("[mtga] draft table callback failed: " + e);
                e.printStackTrace(System.out);
            }
        }
    }
}
