package forge.mtga.fd;

import com.wizards.mtga.fd.FormatConfigInfo;
import com.wizards.mtga.fd.FormatGroup;
import com.wizards.mtga.fd.GetFormatsResponse;
import com.wizards.mtga.fd.Quota;
import forge.StaticData;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.mtga.forge.CardDb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Client deck formats built from Forge's format definitions (forge-gui/res/formats). The client computes a format's
 * legal cards as titles printed in FormatConfigInfo.legalSets (Arena set codes) plus allowedTitleIds, minus
 * bannedTitleIds. Arena reprints carry Arena set codes (Lightning Bolt is STA/FCA/...), so set lists cannot express
 * Forge's legality: every format gets an exact allowedTitleIds list (Forge's card-level filter over all printings) and
 * a unique placeholder legalSet, which matches no card and keeps the client from sharing one format's title list with
 * another (FormatManagerCore.IsSame compares legalSets, bans and flags only).
 */
public final class ForgeFormats {
    /** One client format. forgeFormat null = every card Forge can play (rebalanced "A-" cards only when rebalanced). */
    public record Spec(String name, String forgeFormat, int mainMin, int mainMax, int sideMax, int commandMin, int commandMax,
                       boolean singleton, boolean rebalanced) { }

    /** Deck rules of the draft events (EventUXInfo.DeckSelectFormat): 40 cards from the pool; not a constructed format, so not in the format lists. */
    public static final String LIMITED = "Limited";

    /**
     * A drafted deck kept in the player's own decks (the event page's copy button): a CONSTRUCTED-type format, because the
     * client's deck builder outside an event crashes on a draft-type deck (it reads the event the deck came from).
     * 40 cards, every card allowed.
     */
    public static final String DRAFT_DECK = "Draft";

    /**
     * The format of a new deck before the player picks one (the Decks page's "All Decks" tab creates "Ambiguous"; the client
     * falls back to its default format, Standard, when the name is unknown, which hid every non-Standard card). Every card, 60+ cards.
     * Not in the format groups, so it is no sort-order default.
     */
    public static final String AMBIGUOUS = "Ambiguous";

    /** Draft-type client formats: Limited and its direct-challenge alias (the client may save a copied deck under either). */
    public static boolean isDraftType(String format) {
        if (LIMITED.equals(format)) return true;
        return DIRECT_SLOTS.getOrDefault(LIMITED, List.of()).contains(format);
    }

    /**
     * Arena's Brawl is Commander for two: a commander (legendary creature or planeswalker) and 99 other cards of its colors, singleton, no sideboard,
     * 25 life, best of one with a free first mulligan. For now it has Commander's card pool and ban list, so a Commander deck is as good as a
     * Brawl one in a Brawl queue (the two specs only differ in what may lead the deck).
     */
    public static final String BRAWL = "Brawl";

    /** Client format order (ConstructedSortOrder): the first one is the client's default format. */
    public static final List<Spec> SPECS = List.of(
            new Spec("Standard", "Standard", 60, 250, 15, 0, 0, false, false),
            new Spec("Pioneer", "Pioneer", 60, 250, 15, 0, 0, false, false),
            new Spec("Modern", "Modern", 60, 250, 15, 0, 0, false, false),
            new Spec("Legacy", "Legacy", 60, 250, 15, 0, 0, false, false),
            new Spec("Vintage", "Vintage", 60, 250, 15, 0, 0, false, false),
            new Spec("Pauper", "Pauper", 60, 250, 15, 0, 0, false, false),
            new Spec("Premodern", "Premodern", 60, 250, 15, 0, 0, false, false),
            new Spec("Commander", "Commander", 99, 99, 1, 1, 2, true, false),
            new Spec(BRAWL, "Commander", 99, 99, 1, 1, 2, true, false),
            new Spec("Historic", "Historic", 60, 250, 15, 0, 0, false, true),
            new Spec("Alchemy", null, 60, 250, 15, 0, 0, false, true),
            new Spec("Timeless", null, 60, 250, 15, 0, 0, false, false),
            new Spec(AMBIGUOUS, null, 60, 250, 15, 0, 0, false, false),
            new Spec(DRAFT_DECK, null, 40, 250, 250, 0, 0, false, false),
            new Spec(LIMITED, null, 40, 250, 250, 0, 0, false, false));

    /** Format of the bot-match event: every deck is valid there, the AI deck follows the deck's own format. */
    public static final String ANY = "Timeless";

    private final CardDb db;
    private final Map<String, Set<Integer>> titlesByName = new LinkedHashMap<>();
    private final Map<String, Set<String>> setsByName = new HashMap<>();
    private final Map<String, PaperCard> forgeCards = new HashMap<>();
    private final Map<String, Set<Integer>> legal = new LinkedHashMap<>();

    public ForgeFormats(CardDb db) {
        this.db = db;
        for (CardDb.Printing p : db.printings()) {
            PaperCard pc = forgeCards.computeIfAbsent(p.name(), n -> StaticData.instance().getCommonCards().getUniqueByName(n));
            if (pc == null) continue;
            // Arena-only reprints under a flavor name are legal wherever a paper printing of the same card is
            String real = pc.getRules().getName();
            if (!real.equals(p.name())) {
                java.util.Collection<PaperCard> all = StaticData.instance().getCommonCards().getAllCards(real);
                if (all != null) aliasPrintings.put(p.name(), new ArrayList<>(all));
            }
            titlesByName.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(db.titleId(p.grpId()));
            if (p.expansion() != null && !p.expansion().isBlank()) setsByName.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(p.expansion());
        }
        // Arena-only digital versions under a flavor name (Fire-Brained Scheme = Heroes' Hangout) are separate client rows
        // (not primary cards); they play and are legal exactly like the paper card.
        for (Map.Entry<Integer, String> e : db.namesByGrp().entrySet()) {
            String name = e.getValue();
            if (forgeCards.containsKey(name)) continue;
            PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(name);
            if (pc == null || pc.getRules().getName().equals(name)) continue;
            forgeCards.put(name, pc);
            titlesByName.computeIfAbsent(name, k -> new TreeSet<>()).add(db.titleId(e.getKey()));
            aliasPrintings.put(name, new ArrayList<>(StaticData.instance().getCommonCards().getAllCards(pc.getRules().getName())));
        }
        forgeCards.values().removeIf(java.util.Objects::isNull);
    }

    /** Forge's definition of a client format (null for the "every card" formats or unknown names). */
    public static GameFormat forgeFormat(String clientFormat) {
        for (Spec s : SPECS) {
            if (s.name().equalsIgnoreCase(clientFormat)) return s.forgeFormat() == null ? null : FModel.getFormats().getFormat(s.forgeFormat());
        }
        return null;
    }

    /** Names (Forge spelling) of the client's cards that are legal in a client format. */
    public synchronized Set<String> legalNames(Spec spec) {
        Set<String> out = new LinkedHashSet<>();
        GameFormat gf = spec.forgeFormat() == null ? null : FModel.getFormats().getFormat(spec.forgeFormat());
        Predicate<PaperCard> rules = gf == null ? null : gf.getFilterRules();
        for (Map.Entry<String, PaperCard> e : forgeCards.entrySet()) {
            boolean ok = rules != null ? (rules.test(e.getValue()) || anyAliasLegal(rules, e.getKey())) : spec.rebalanced() || !e.getKey().startsWith("A-");
            if (ok) out.add(e.getKey());
        }
        return out;
    }

    /** Paper printings of cards the client knows under a flavor name (Fire-Brained Scheme = Heroes' Hangout). */
    private final Map<String, List<PaperCard>> aliasPrintings = new HashMap<>();

    private boolean anyAliasLegal(Predicate<PaperCard> rules, String clientName) {
        for (PaperCard other : aliasPrintings.getOrDefault(clientName, List.of())) if (rules.test(other)) return true;
        return false;
    }

    private Set<Integer> titles(Iterable<String> names) {
        Set<Integer> out = new TreeSet<>();
        for (String n : names) {
            Set<Integer> t = titlesByName.get(n);
            if (t != null) out.addAll(t);
        }
        return out;
    }

    /** Format of the direct challenge screen's slot name (client format names): Standard / Pioneer / Modern / Limited rules under the slot's own name. */
    private static final Map<String, List<String>> DIRECT_SLOTS = Map.of("Standard", List.of("DirectGame"), "Pioneer", List.of("DirectGameBrawlRebalanced"),
            "Modern", List.of("DirectGameAlchemy"), "Legacy", List.of("DirectGameLimited"), "Vintage", List.of("DirectGameVintage"),
            "Pauper", List.of("DirectGamePauper"), "Premodern", List.of("DirectGamePremodern"), BRAWL, List.of("DirectGameBrawl"),
            "Historic", List.of("DirectGameHistoric"));

    public synchronized GetFormatsResponse response() {
        GetFormatsResponse.Builder b = GetFormatsResponse.newBuilder();
        List<String> order = new ArrayList<>();
        for (Spec s : SPECS) {
            GameFormat gf = s.forgeFormat() == null ? null : FModel.getFormats().getFormat(s.forgeFormat());
            if (s.forgeFormat() != null && gf == null) {
                System.out.println("[mtga] FD formats: Forge has no format " + s.forgeFormat() + ", skipping " + s.name());
                continue;
            }
            Set<String> names = legalNames(s);
            Set<Integer> allowed = titles(names);
            legal.put(s.name(), allowed);
            Set<String> filterSets = new TreeSet<>();
            for (String n : names) filterSets.addAll(setsByName.getOrDefault(n, Set.of()));
            FormatConfigInfo.Builder f = FormatConfigInfo.newBuilder().setName(s.name())
                    .addLegalSets("FORGE_" + s.name().toUpperCase())
                    .addAllFilterSets(filterSets)
                    .addAllAllowedTitleIds(allowed)
                    .setFormatTypeInternal(LIMITED.equals(s.name()) ? 1 : 3)   // EFormatType.Draft : Constructed
                    .setMainDeckQuota(Quota.newBuilder().setMin(s.mainMin()).setMax(s.mainMax()))
                    .setSideBoardQuota(Quota.newBuilder().setMin(0).setMax(s.sideMax()))
                    .setUseRebalancedCards(s.rebalanced());
            if (s.singleton()) f.setCardCountRestrictionInternal(1);   // CardCountRule.Singleton
            if (s.commandMax() > 0) {
                f.setCommandZoneQuota(Quota.newBuilder().setMin(s.commandMin()).setMax(s.commandMax()))
                        .setSideboardBehaviorInternal(1);   // FormatSideboardBehavior.CompanionOnly
            }
            int banned = 0, restricted = 0;
            if (gf != null) {
                // listed bans stay visible in the deck builder, marked banned; other illegal cards are hidden
                Set<Integer> bannedTitles = titles(gf.getBannedCardNames());
                f.addAllBannedTitleIds(bannedTitles);
                banned = bannedTitles.size();
                for (int t : titles(gf.getRestrictedCards())) {
                    f.putIndividualCardQuotas(t, Quota.newBuilder().setMin(0).setMax(1).build());
                    restricted++;
                }
            }
            b.addFormats(f);
            for (String alias : DIRECT_SLOTS.getOrDefault(s.name(), List.of())) {      // the challenge screen's fixed slots, re-skinned (docs/online-identity-friends.md, section 8)
                b.addFormats(f.clone().setName(alias));
                legal.put(alias, allowed);
            }
            if (!LIMITED.equals(s.name()) && !AMBIGUOUS.equals(s.name())) order.add(s.name());
            System.out.println("[mtga] FD format " + s.name() + ": " + names.size() + " legal cards (" + allowed.size() + " titles), "
                    + banned + " banned, " + restricted + " restricted, " + filterSets.size() + " Arena sets");
        }
        b.addFormatGroups(FormatGroup.newBuilder().setGroupName("ConstructedSortOrder").addAllFormatNames(order));
        b.addFormatGroups(FormatGroup.newBuilder().setGroupName("EvergreenFormats").addAllFormatNames(order));
        return b.build();
    }


    private final Map<String, Set<String>> legalByFormat = new HashMap<>();

    /**
     * Server-side deck check for a queue whose deck format is {@code format}: null if the main deck (Forge names -> quantity) is
     * playable there, else the reason. {@code unknownCards} = cards of the deck Forge could not resolve (never legal).
     */
    public synchronized String problem(String format, Map<String, Integer> main, int unknownCards) { return problem(format, main, List.of(), unknownCards); }

    /** The same with the deck's command zone cards (Forge names): a commander format needs its commander, any other has none. */
    public synchronized String problem(String format, Map<String, Integer> main, List<String> commanders, int unknownCards) {
        Spec spec = null;
        for (Spec s : SPECS) if (s.name().equalsIgnoreCase(format)) spec = s;
        if (spec == null) return "unknown format " + format;
        if (unknownCards > 0) return "the deck has " + unknownCards + " card(s) that are not legal in " + spec.name();
        Set<String> ok = legalByFormat.computeIfAbsent(spec.name(), k -> legalNames(SPECS.stream().filter(x -> x.name().equals(k)).findFirst().get()));
        int total = 0;
        for (Map.Entry<String, Integer> e : main.entrySet()) {
            total += e.getValue();
            if (!ok.contains(e.getKey())) return e.getKey() + " is not legal in " + spec.name();
            PaperCard pc = forgeCards.get(e.getKey());
            boolean unlimited = pc != null && (pc.getRules().getType().isBasicLand()
                    || pc.getRules().getOracleText().contains("A deck can have any number of cards named"));
            if (!unlimited && spec.singleton() && e.getValue() > 1) return "more than one copy of " + e.getKey() + " in a " + spec.name() + " deck";
            if (!unlimited && e.getValue() > 4) return "more than 4 copies of " + e.getKey();
            if (e.getValue() > 1 && restricted(spec).contains(e.getKey())) return e.getKey() + " is restricted in " + spec.name() + " (one copy)";
        }
        if (total < spec.mainMin()) return "a " + spec.name() + " deck needs at least " + spec.mainMin() + " cards (this one has " + total + ")";
        if (spec.commandMax() > 0 && total > spec.mainMax()) return "a " + spec.name() + " deck has at most " + spec.mainMax() + " cards besides its commander (this one has " + total + ")";
        if (commanders.size() < spec.commandMin() || commanders.size() > spec.commandMax()) {
            return spec.commandMax() == 0 ? spec.name() + " decks have no commander" : "a " + spec.name() + " deck needs " + spec.commandMin() + " commander (this one has " + commanders.size() + ")";
        }
        int identity = 0;
        for (String c : commanders) {
            PaperCard pc = forgeCards.get(c);
            if (!ok.contains(c)) return c + " is not legal in " + spec.name();
            if (pc == null) continue;
            boolean can = BRAWL.equals(spec.name()) ? pc.getRules().canBeBrawlCommander() : pc.getRules().canBeCommander();
            if (!can) return c + " can't be a " + spec.name() + " commander";
            identity |= pc.getRules().getColorIdentity().getColor();
        }
        if (!commanders.isEmpty()) {
            for (String n : main.keySet()) {
                PaperCard pc = forgeCards.get(n);
                if (pc != null && !pc.getRules().getColorIdentity().hasNoColorsExcept(identity)) return n + " is outside your commander's colors";
            }
        }
        return null;
    }

    private final Map<String, Set<String>> restrictedByFormat = new HashMap<>();

    /** Forge's restricted list of a format (Vintage): one copy each. */
    private Set<String> restricted(Spec spec) {
        return restrictedByFormat.computeIfAbsent(spec.name(), k -> {
            GameFormat gf = spec.forgeFormat() == null ? null : FModel.getFormats().getFormat(spec.forgeFormat());
            return gf == null ? Set.of() : new java.util.HashSet<>(gf.getRestrictedCards());
        });
    }

    /** Title ids legal in a client format (after {@link #response()}), null if unknown. */
    public synchronized Set<Integer> legalTitles(String format) { return legal.get(format); }

    /** Does the client have a printing (GrpId) for this Forge card name? */
    public boolean onClient(String forgeName) { return db.grpId(forgeName) != 0; }
}
