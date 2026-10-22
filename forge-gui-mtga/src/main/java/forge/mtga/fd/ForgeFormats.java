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

    /** Client format order (ConstructedSortOrder): the first one is the client's default format. */
    public static final List<Spec> SPECS = List.of(
            new Spec("Standard", "Standard", 60, 250, 15, 0, 0, false, false),
            new Spec("Pioneer", "Pioneer", 60, 250, 15, 0, 0, false, false),
            new Spec("Modern", "Modern", 60, 250, 15, 0, 0, false, false),
            new Spec("Legacy", "Legacy", 60, 250, 15, 0, 0, false, false),
            new Spec("Vintage", "Vintage", 60, 250, 15, 0, 0, false, false),
            new Spec("Pauper", "Pauper", 60, 250, 15, 0, 0, false, false),
            new Spec("Commander", "Commander", 99, 99, 1, 1, 2, true, false),
            new Spec("Historic", "Historic", 60, 250, 15, 0, 0, false, true),
            new Spec("Alchemy", null, 60, 250, 15, 0, 0, false, true),
            new Spec("Timeless", null, 60, 250, 15, 0, 0, false, false),
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
            titlesByName.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(db.titleId(p.grpId()));
            if (p.expansion() != null && !p.expansion().isBlank()) setsByName.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(p.expansion());
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
            boolean ok = rules != null ? rules.test(e.getValue()) : spec.rebalanced() || !e.getKey().startsWith("A-");
            if (ok) out.add(e.getKey());
        }
        return out;
    }

    private Set<Integer> titles(Iterable<String> names) {
        Set<Integer> out = new TreeSet<>();
        for (String n : names) {
            Set<Integer> t = titlesByName.get(n);
            if (t != null) out.addAll(t);
        }
        return out;
    }

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
            if (!LIMITED.equals(s.name())) order.add(s.name());
            System.out.println("[mtga] FD format " + s.name() + ": " + names.size() + " legal cards (" + allowed.size() + " titles), "
                    + banned + " banned, " + restricted + " restricted, " + filterSets.size() + " Arena sets");
        }
        b.addFormatGroups(FormatGroup.newBuilder().setGroupName("ConstructedSortOrder").addAllFormatNames(order));
        b.addFormatGroups(FormatGroup.newBuilder().setGroupName("EvergreenFormats").addAllFormatNames(order));
        return b.build();
    }

    /** Title ids legal in a client format (after {@link #response()}), null if unknown. */
    public synchronized Set<Integer> legalTitles(String format) { return legal.get(format); }

    /** Does the client have a printing (GrpId) for this Forge card name? */
    public boolean onClient(String forgeName) { return db.grpId(forgeName) != 0; }
}
