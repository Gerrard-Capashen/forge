package forge.mtga.forge;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only view of the client's SQLite card database: GrpId &lt;-&gt; English card name, card abilities, prompts. */
public final class CardDb {
    private final File file;
    private final Map<Integer, String> nameByGrp = new HashMap<>();
    private final Map<String, Integer> grpByName = new HashMap<>();
    private final Map<Integer, Integer> titleByGrp = new HashMap<>();
    private final Map<Integer, String> abilityIdsByGrp = new HashMap<>();
    private final Map<Integer, List<Ability>> abilityCache = new HashMap<>();
    private final Map<String, Integer> promptByText = new HashMap<>();
    private final Map<Integer, String> linkedTokens = new HashMap<>();
    private final Map<Integer, Integer> linkedFace = new HashMap<>();      // card grpId -> first linked face (adventure half...)
    private final Map<String, Integer> keywordAbility = new HashMap<>();   // "haste" -> 9 (static keyword abilities)
    private final Map<String, List<int[]>> tokensByName = new HashMap<>();   // name -> {grpId, power, toughness}

    /** A collectible (primary, non-token) printing: what the Front Door's collection and formats are built from. */
    public record Printing(int grpId, String name, String expansion, String digitalSet, int artId) { }

    private final List<Printing> printings = new ArrayList<>();

    private final Map<Integer, Integer> artByGrp = new HashMap<>();

    /** Art id of a printing (deck box art), 0 if unknown. */
    public synchronized int artId(int grpId) {
        if (artByGrp.isEmpty()) for (Printing p : printings) artByGrp.put(p.grpId(), p.artId());
        return artByGrp.getOrDefault(grpId, 0);
    }

    /** All primary non-token printings of the client's card database. */
    public List<Printing> printings() { return printings; }

    /** One printed ability of a card: client ability id, category (1 activated, 2 triggered, 3 static, 4 spell, ...), loyalty cost, English text. */
    public record Ability(int id, int category, String loyaltyCost, String text, String modalChildIds) { }

    public static File findDatabase(File clientDir) {
        File raw = new File(clientDir, "MTGA_Data/Downloads/Raw");
        File[] files = raw.listFiles((d, n) -> n.startsWith("Raw_CardDatabase_") && n.endsWith(".mtga"));
        if (files == null || files.length == 0) throw new IllegalStateException("no Raw_CardDatabase_*.mtga in " + raw);
        return files[0];
    }

    public CardDb(File dbFile) throws Exception {
        this.file = dbFile;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            // Titles: Formatted=1 is the display text and the only row for most cards; when it carries markup (the
            // Alchemy "A-" sprite) there is also a plain Formatted=0 row. Split cards are titled "Commit /// Memory".
            try (ResultSet rs = st.executeQuery(
                    "select c.GrpId, coalesce(p.Loc, f.Loc), c.IsPrimaryCard, c.IsToken, c.IsDigitalOnly, c.IsRebalanced, c.TitleId, c.AbilityIds, "
                            + "c.AbilityIdToLinkedTokenGrpId, c.Power, c.Toughness, c.LinkedFaceGrpIds, c.ExpansionCode, c.DigitalReleaseSet, c.ArtId "
                            + "from Cards c left join Localizations_enUS p on p.LocId = c.TitleId and p.Formatted = 0 "
                            + "left join Localizations_enUS f on f.LocId = c.TitleId and f.Formatted = 1 where coalesce(p.Loc, f.Loc) is not null")) {
                while (rs.next()) {
                    int grp = rs.getInt(1);
                    String name = plainTitle(rs.getString(2));
                    nameByGrp.put(grp, name);
                    titleByGrp.put(grp, rs.getInt(7));
                    abilityIdsByGrp.put(grp, rs.getString(8));
                    String faces = rs.getString(12);
                    if (faces != null && !faces.isBlank()) linkedFace.put(grp, num(faces.split(",")[0]));
                    String linked = rs.getString(9);
                    if (linked != null && !linked.isBlank()) linkedTokens.put(grp, linked);
                    if (rs.getInt(4) == 1) tokensByName.computeIfAbsent(name, k -> new ArrayList<>()).add(new int[] {grp, num(rs.getString(10)), num(rs.getString(11))});
                    // preferred printing for a name: primary, not a token, not digital-only/rebalanced; highest GrpId wins
                    boolean good = rs.getInt(3) == 1 && rs.getInt(4) == 0 && rs.getInt(5) == 0 && rs.getInt(6) == 0;
                    if (rs.getInt(3) == 1 && rs.getInt(4) == 0) printings.add(new Printing(grp, name, rs.getString(13), rs.getString(14), rs.getInt(15)));
                    if (good) grpByName.merge(name, grp, Math::max);
                    else grpByName.putIfAbsent(name, grp);
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "select a.Id, l.Loc from Abilities a join Localizations_enUS l on l.LocId = a.TextId "
                            + "where a.Category = 3 and length(l.Loc) < 30 order by a.Id")) {
                while (rs.next()) keywordAbility.putIfAbsent(rs.getString(2).trim().toLowerCase(), rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery(
                    "select p.Id, l.Loc from Prompts p join Localizations_enUS l on l.LocId = p.LocId")) {
                while (rs.next()) promptByText.putIfAbsent(normalize(rs.getString(2)), rs.getInt(1));
            }
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
    }

    /** Card title as Forge names it: no rich-text tags, split cards "A // B" (the DB writes "A /// B"). */
    public static String plainTitle(String loc) {
        return loc.replaceAll("<[^>]+>", "").replace(" /// ", " // ").trim();
    }

    private static int num(String s) {
        try { return s == null || s.isBlank() ? -1 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    public String name(int grpId) { return nameByGrp.get(grpId); }

    /** The other face of a two-faced/adventure printing (Bonecrusher Giant -> Stomp), 0 if none. */
    public int linkedFace(int grpId) { return Math.max(0, linkedFace.getOrDefault(grpId, 0)); }

    /** Client ability id of a plain keyword ("Haste" -> 9), 0 if unknown. */
    public int keywordAbilityId(String keyword) {
        return keyword == null ? 0 : keywordAbility.getOrDefault(keyword.trim().toLowerCase(), 0);
    }

    /**
     * GrpId for a token: first a token the source card links to (Cards.AbilityIdToLinkedTokenGrpId) with that name,
     * else any token printing with that name and P/T. Forge names tokens "X Token".
     */
    public int tokenGrpId(String forgeName, int sourceGrp, int power, int toughness) {
        String name = forgeName.endsWith(" Token") ? forgeName.substring(0, forgeName.length() - 6) : forgeName;
        String linked = linkedTokens.get(sourceGrp);
        if (linked != null) {
            for (String part : linked.split(",")) {
                String[] kv = part.split(":");
                if (kv.length < 2) continue;
                int tg = Integer.parseInt(kv[1].trim());
                if (name.equals(nameByGrp.get(tg))) return tg;
            }
        }
        List<int[]> cands = tokensByName.get(name);
        if (cands == null || cands.isEmpty()) return 0;
        int best = cands.get(0)[0];
        for (int[] t : cands) {
            if (t[1] == power && t[2] == toughness) { best = Math.max(best, t[0]); }
        }
        return best;
    }

    /** Best GrpId for a card name (0 if the client has no such card). */
    public int grpId(String name) { return grpByName.getOrDefault(name, 0); }

    /** Localization id of the card's title (GameObjectInfo.name), 0 if unknown. */
    public int titleId(int grpId) { return titleByGrp.getOrDefault(grpId, 0); }

    /** Prompt id whose English text equals the given text (ignoring case/punctuation), 0 if none. */
    public int promptId(String text) {
        return text == null ? 0 : promptByText.getOrDefault(normalize(text), 0);
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("<[^>]+>", "").replaceAll("[^a-z0-9{}+/-]+", " ").trim();
    }

    /** The printed abilities of a card (AbilityIds order), with their text. */
    public synchronized List<Ability> abilities(int grpId) {
        List<Ability> cached = abilityCache.get(grpId);
        if (cached != null) return cached;
        List<Ability> out = new ArrayList<>();
        String ids = abilityIdsByGrp.get(grpId);
        if (ids != null && !ids.isBlank()) {
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                    "select a.Id, a.Category, a.LoyaltyCost, l.Loc, a.ModalChildIds from Abilities a "
                            + "left join Localizations_enUS l on l.LocId = a.TextId where a.Id = ?")) {
                for (String part : ids.split(",")) {
                    String id = part.split(":")[0].trim();
                    if (id.isEmpty()) continue;
                    ps.setInt(1, Integer.parseInt(id));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) out.add(new Ability(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                    }
                }
            } catch (Exception e) {
                System.out.println("[mtga] ability lookup failed for " + grpId + ": " + e);
            }
        }
        abilityCache.put(grpId, out);
        return out;
    }

    /** Text of any ability id (modal children are not in a card's AbilityIds list). */
    public synchronized Ability ability(int abilityId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "select a.Id, a.Category, a.LoyaltyCost, l.Loc, a.ModalChildIds from Abilities a "
                        + "left join Localizations_enUS l on l.LocId = a.TextId where a.Id = ?")) {
            ps.setInt(1, abilityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Ability(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5));
            }
        } catch (Exception e) {
            System.out.println("[mtga] ability lookup failed for " + abilityId + ": " + e);
        }
        return null;
    }
}
