package forge.mtga;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.game.GameFormat;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.HeadlessGui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Extends the client's card database with Forge cards it does not have, so they get a GrpId (collection, deck builder,
 * formats, GRE) like any Arena card. Meant to be re-run on every machine / client update: it always starts from a
 * pristine manifest + card DB (never modified) and writes the result into the patched client copy only.
 *
 * <ol>
 * <li>Source: {@code --source <Downloads dir>} (e.g. a Steam install, read only) or, by default, the snapshot
 *     {@code data/carddb/pristine}, taken from the client copy the first time.</li>
 * <li>Copies the source DB and adds one Cards row per Forge card face (legal in the chosen formats, in format order),
 *     cloned from a template row. Multi-face cards are linked the client's way (LinkedFaceType = type of the linked face):
 *     DFC/MDFC/flip front + back, adventure/omen/prepare parent + child spell, split/Room card + two halves (the card row
 *     unites both halves). Per face: type line, cost, colors, P/T (planeswalkers: loyalty in Toughness), rarity,
 *     Forge set, oracle text as Abilities rows (existing client abilities reused when the text matches, so keywords keep
 *     their icons), titles/texts in every Localizations_* table. Ids from reserved ranges (see constants).</li>
 * <li>The DB is renamed Raw_CardDatabase_&lt;md5&gt;.mtga and listed (Length, sha256) in a copy of the source manifest,
 *     itself named Manifest_&lt;md5 of its bytes&gt;.mtga, which replaces the copy's main manifest. The client only keeps
 *     files a manifest lists, and the bridge's LocalDoorbell offers the manifests found on disk.</li>
 * </ol>
 *
 * <p>{@code --carddata <dir>} holds {@code pristine/} and {@code state.json} (default {@code <client>/../data/carddb}; the
 * patcher passes one per target so two client versions never share a pristine snapshot).
 *
 * <p>{@code --cubes "A;B"}: also every card of these Forge cubes (default: the premier draft cube, see {@link forge.mtga.draft.Cubes}),
 * after the format cards. {@code --cubes ""} adds none.
 *
 * <p>{@code --more-formats Legacy,Vintage,Pauper} (the default): the cards of these formats that are still missing, after the
 * cube cards. Formats added later go at the end like this, so the GrpIds of the cards built before them never move (saved decks
 * keep pointing at the same cards). {@code --more-formats ""} adds none.
 *
 * <p>Last, the cards of all those formats that the client has only as non-primary rows (Urza's Mine, Serum Visions: Alchemy
 * bonus-sheet printings). The client's formats and deck validator ignore such rows, so they get a primary row of their own.
 * Then non-primary rows for game pieces Forge puts in the command zone that the client lacks (The Initiative, Undercity).
 *
 * <p>Args: forgeGuiDir clientDir [--source dir] [--carddata dir] [--formats Standard,Pioneer,Modern] [--limit n] [--names "A;B"] [--cubes "A;B"] [--more-formats L,V,P] [--dry-run]
 */
public final class CardDbBuilder {
    public static final int GRP_BASE = 9_000_000;
    /** Separate from GRP_BASE: ability objects on the stack carry their ability id as GrpId in GRE messages. */
    static final int ABILITY_BASE = 9_500_000;
    static final int ART_BASE = 9_000_000;
    static final int LOC_BASE = 900_000_000;
    /** Template rows cloned for new cards / abilities (Grizzly Bears, Lightning Bolt's spell ability). */
    static final int TEMPLATE_CARD = 79334;
    static final int TEMPLATE_ABILITY = 70361;
    private static final Pattern MAIN_MANIFEST = Pattern.compile("^Manifest_([0-9a-f]{32})\\.mtga$");
    private static final Pattern SYMBOL = Pattern.compile("\\{([^}]+)\\}");
    private static final Pattern SYMBOL_RUN = Pattern.compile("(?:\\{[^}]+\\})+");
    private static final Pattern LOYALTY = Pattern.compile("^\\[?([+−-]?(?:\\d+|X))\\]?:\\s*(.*)$");

    record Source(File manifest, File db) { }

    public static void main(String[] args) throws Exception {
        File guiDir = new File(args[0]);
        File clientDir = new File(args[1]).getCanonicalFile();
        Map<String, String> opt = options(args);
        if (clientDir.getPath().toLowerCase(Locale.ROOT).contains("steamapps")) {
            throw new IllegalArgumentException("refusing to write into " + clientDir + ": target must be the patched copy, not a Steam install");
        }
        File downloads = new File(clientDir, "MTGA_Data/Downloads");
        File dataDir = opt.containsKey("carddata") ? new File(opt.get("carddata")).getCanonicalFile() : new File(clientDir.getParentFile(), "data/carddb");
        Source src = source(opt.get("source"), downloads, new File(dataDir, "pristine"));
        System.out.println("[carddb] source manifest " + src.manifest() + "\n[carddb] source DB " + src.db());

        GuiBase.setInterface(new HeadlessGui(guiDir));
        FModel.initialize(null, null);

        File raw = new File(downloads, "Raw");
        File work = new File(raw, "Raw_CardDatabase_forge-building");
        Files.copy(src.db().toPath(), work.toPath(), StandardCopyOption.REPLACE_EXISTING);
        int added;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + work.getAbsolutePath())) {
            c.setAutoCommit(false);
            Builder b = new Builder(c);
            List<PaperCard> cards = b.candidates(List.of(opt.getOrDefault("formats", "Standard,Pioneer,Modern").split(",")),
                    Integer.parseInt(opt.getOrDefault("limit", "0")), opt.containsKey("names") ? List.of(opt.get("names").split(";")) : List.of());
            // cube cards go last, so adding a cube never moves the GrpIds the format cards already have
            String cubes = opt.containsKey("cubes") ? opt.get("cubes") : forge.mtga.draft.Cubes.premierCube();
            int before = cards.size();
            for (String cube : cubes == null || cubes.isBlank() ? new String[0] : cubes.split(";")) {
                for (PaperCard pc : forge.mtga.draft.Cubes.cards(cube.trim())) {
                    PaperCard first = Builder.firstPrinting(pc);
                    if (b.supported(first) && cards.stream().noneMatch(x -> x.getName().equals(first.getName()))) cards.add(first);
                }
            }
            System.out.println("[carddb] cubes " + cubes + ": " + (cards.size() - before) + " more cards");
            String more = opt.containsKey("more-formats") ? opt.get("more-formats") : "Legacy,Vintage,Pauper";
            if (more != null && !more.isBlank()) {
                before = cards.size();
                java.util.Set<String> have = new java.util.HashSet<>();
                for (PaperCard pc : cards) have.add(pc.getName());
                for (PaperCard pc : b.candidates(List.of(more.split(",")), 0, List.of())) if (have.add(pc.getName())) cards.add(pc);
                System.out.println("[carddb] more formats " + more + ": " + (cards.size() - before) + " more cards");
            }
            before = cards.size();
            java.util.Set<String> have = new java.util.HashSet<>();
            for (PaperCard pc : cards) have.add(pc.getName());
            List<String> all = new ArrayList<>(List.of(opt.getOrDefault("formats", "Standard,Pioneer,Modern").split(",")));
            if (more != null && !more.isBlank()) all.addAll(List.of(more.split(",")));
            b.nonPrimaryOnly = true;
            for (PaperCard pc : b.candidates(all, 0, List.of())) if (have.add(pc.getName())) cards.add(pc);
            System.out.println("[carddb] cards the client has only as non-primary rows: " + (cards.size() - before) + " more cards");
            for (PaperCard pc : cards) b.add(pc);
            int pieces = b.addGamePieces();
            System.out.println("[carddb] command zone pieces: " + pieces);
            c.commit();
            added = cards.size();
            System.out.println("[carddb] added " + added + " cards, " + b.newAbilities + " new abilities, " + b.reusedAbilities
                    + " reused abilities, " + b.unmappedSubtypes.size() + " subtypes without a client enum " + b.unmappedSubtypes);
        }
        if (opt.containsKey("dry-run")) {
            System.out.println("[carddb] dry run: built " + work + ", client copy unchanged");
            return;
        }
        install(work, src, downloads, dataDir, added);
    }

    // ---- source / install -----------------------------------------------------------------------------------------

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            m.put(k, flag ? "true" : args[++i]);
        }
        return m;
    }

    private static Source source(String explicit, File targetDownloads, File pristine) throws Exception {
        if (explicit != null) return findSource(new File(explicit));
        if (new File(pristine, "Raw").isDirectory()) return findSource(pristine);
        // first run: snapshot the copy's own (still unmodified) manifest + DB
        Source cur = findSource(targetDownloads);
        if (isGenerated(cur.db())) {
            throw new IllegalStateException("the client copy already has a generated card DB and no pristine snapshot exists: pass --source <Downloads of an MTGA install of the same version>");
        }
        new File(pristine, "Raw").mkdirs();
        Files.copy(cur.manifest().toPath(), new File(pristine, cur.manifest().getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cur.db().toPath(), new File(pristine, "Raw/" + cur.db().getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[carddb] pristine snapshot saved in " + pristine);
        return findSource(pristine);
    }

    private static boolean isGenerated(File db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select count(*) from Cards where GrpId >= " + GRP_BASE)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** The main (category-less) manifest of a Downloads dir and the card DB it lists. */
    private static Source findSource(File dir) throws Exception {
        File[] manifests = dir.listFiles((d, n) -> MAIN_MANIFEST.matcher(n).matches());
        if (manifests != null) {
            for (File m : manifests) {
                JsonObject asset = cardDbAsset(readManifest(m));
                if (asset == null) continue;
                File db = new File(dir, "Raw/" + asset.get("Name").getAsString());
                if (!db.isFile()) throw new IllegalStateException(m + " lists " + db + " which is missing");
                return new Source(m, db);
            }
        }
        throw new IllegalStateException("no manifest listing a Raw_CardDatabase in " + dir);
    }

    static JsonObject readManifest(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) { bytes = in.readAllBytes(); }
        }
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject cardDbAsset(JsonObject manifest) {
        for (JsonElement e : manifest.getAsJsonArray("Assets")) {
            JsonObject a = e.getAsJsonObject();
            if (a.get("Name").getAsString().startsWith("Raw_CardDatabase_")) return a;
        }
        return null;
    }

    private static void install(File work, Source src, File downloads, File dataDir, int added) throws Exception {
        byte[] sha = digest("SHA-256", work);
        String dbName = "Raw_CardDatabase_" + hex(digest("MD5", work)) + ".mtga";
        File raw = new File(downloads, "Raw");
        File db = new File(raw, dbName);
        Files.move(work.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING);

        JsonObject manifest = readManifest(src.manifest());
        JsonObject asset = cardDbAsset(manifest);
        asset.addProperty("Name", dbName);
        asset.addProperty("Length", db.length());
        asset.addProperty("sha256", Base64.getEncoder().encodeToString(sha));
        byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        String manifestName = "Manifest_" + hex(MessageDigest.getInstance("MD5").digest(bytes)) + ".mtga";
        Files.write(new File(downloads, manifestName).toPath(), bytes);

        // only one main manifest / card DB may remain: the client deletes what no manifest lists, the bridge offers every manifest
        File[] old = downloads.listFiles((d, n) -> MAIN_MANIFEST.matcher(n).matches() && !n.equals(manifestName));
        if (old != null) for (File f : old) { Files.delete(f.toPath()); System.out.println("[carddb] removed " + f.getName()); }
        File[] oldDbs = raw.listFiles((d, n) -> n.startsWith("Raw_CardDatabase_") && !n.equals(dbName));
        if (oldDbs != null) for (File f : oldDbs) { Files.delete(f.toPath()); System.out.println("[carddb] removed Raw/" + f.getName()); }

        JsonObject state = new JsonObject();
        state.addProperty("sourceManifest", src.manifest().getAbsolutePath());
        state.addProperty("sourceDb", src.db().getAbsolutePath());
        state.addProperty("manifest", manifestName);
        state.addProperty("db", dbName);
        state.addProperty("cardsAdded", added);
        state.addProperty("built", java.time.OffsetDateTime.now().toString());
        dataDir.mkdirs();
        Files.writeString(new File(dataDir, "state.json").toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(state));
        System.out.println("[carddb] installed " + dbName + " (" + db.length() + " bytes) via " + manifestName);
    }

    private static byte[] digest(String algo, File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return md.digest();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- rows -----------------------------------------------------------------------------------------------------

    static final class Builder {
        final Connection c;
        final Set<String> existingNames = new HashSet<>();
        /** Names with a primary, non-token row: what the client's formats and collection know. */
        final Set<String> primaryNames = new HashSet<>();
        /** Candidates are the cards that exist only as non-primary rows (instead of the cards the client lacks). */
        boolean nonPrimaryOnly;
        final Map<String, Integer> cardTypes = new HashMap<>(), superTypes = new HashMap<>(), subTypes = new HashMap<>();
        final Map<String, Integer> locByText = new HashMap<>();
        final Map<String, Integer> abilityByText = new HashMap<>();
        final Map<Integer, Integer> abilityText = new HashMap<>();   // ability id -> its TextId
        final List<String> langTables = new ArrayList<>();
        final String[] cardCols, abilityCols;
        final Object[] cardTemplate, abilityTemplate;
        final PreparedStatement insCard, insAbility, insLocId;
        final Map<String, PreparedStatement> insLoc = new LinkedHashMap<>();
        final Set<String> unmappedSubtypes = new java.util.TreeSet<>();
        int nextGrp = GRP_BASE, nextAbility = ABILITY_BASE, nextLoc = LOC_BASE, nextArt = ART_BASE;
        int newAbilities, reusedAbilities;

        Builder(Connection c) throws Exception {
            this.c = c;
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("select coalesce(p.Loc, f.Loc), c.IsPrimaryCard, c.IsToken from Cards c left join Localizations_enUS p on p.LocId = c.TitleId and p.Formatted = 0 "
                        + "left join Localizations_enUS f on f.LocId = c.TitleId and f.Formatted = 1")) {
                    while (rs.next()) {
                        if (rs.getString(1) == null) continue;
                        String name = CardDb.plainTitle(rs.getString(1));
                        existingNames.add(name);
                        if (rs.getInt(2) == 1 && rs.getInt(3) == 0) primaryNames.add(name);
                    }
                }
                try (ResultSet rs = st.executeQuery("select e.Type, e.Value, l.Loc from Enums e join Localizations_enUS l on l.LocId = e.LocId and l.Formatted = 1")) {
                    while (rs.next()) {
                        Map<String, Integer> m = switch (rs.getString(1)) { case "CardType" -> cardTypes; case "SuperType" -> superTypes; case "SubType" -> subTypes; default -> null; };
                        if (m != null) m.putIfAbsent(rs.getString(3), rs.getInt(2));
                    }
                }
                try (ResultSet rs = st.executeQuery("select LocId, Loc from Localizations_enUS where Formatted = 1 order by LocId")) {
                    while (rs.next()) if (rs.getString(2) != null) locByText.putIfAbsent(rs.getString(2), rs.getInt(1));
                }
                try (ResultSet rs = st.executeQuery("select a.Id, a.TextId, l.Loc from Abilities a join Localizations_enUS l on l.LocId = a.TextId and l.Formatted = 1 "
                        // a planeswalker ability ("+1: Draw a card.") must not be reused for a plain line: the card would show its loyalty badge
                        + "where a.LoyaltyCost is null or a.LoyaltyCost = '' order by a.Id")) {
                    while (rs.next()) {
                        abilityByText.putIfAbsent(rs.getString(3), rs.getInt(1));
                        abilityText.put(rs.getInt(1), rs.getInt(2));
                    }
                }
                try (ResultSet rs = st.executeQuery("select name from sqlite_master where type = 'table' and name like 'Localizations\\_%' escape '\\'")) {
                    while (rs.next()) langTables.add(rs.getString(1));
                }
                try (ResultSet rs = st.executeQuery("select max(GrpId), (select max(Id) from Abilities), (select max(LocId) from Localizations) from Cards")) {
                    if (rs.next() && (rs.getInt(1) >= GRP_BASE || rs.getInt(2) >= ABILITY_BASE || rs.getInt(3) >= LOC_BASE)) {
                        throw new IllegalStateException("source DB already uses the reserved id ranges (generated DB as source?)");
                    }
                }
            }
            Object[][] card = row("select * from Cards where GrpId = " + TEMPLATE_CARD);
            cardCols = Arrays.stream(card[0]).map(Object::toString).toArray(String[]::new);
            cardTemplate = card[1];
            Object[][] ab = row("select * from Abilities where Id = " + TEMPLATE_ABILITY);
            abilityCols = Arrays.stream(ab[0]).map(Object::toString).toArray(String[]::new);
            abilityTemplate = ab[1];
            insCard = c.prepareStatement(insert("Cards", cardCols));
            insAbility = c.prepareStatement(insert("Abilities", abilityCols));
            insLocId = c.prepareStatement("insert or ignore into Localizations (LocId) values (?)");
            for (String t : langTables) insLoc.put(t, c.prepareStatement("insert into " + t + " (LocId, Formatted, Loc) values (?, 1, ?)"));
        }

        private Object[][] row(String sql) throws Exception {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) throw new IllegalStateException("template row missing: " + sql);
                ResultSetMetaData md = rs.getMetaData();
                Object[] names = new Object[md.getColumnCount()], vals = new Object[md.getColumnCount()];
                for (int i = 0; i < names.length; i++) { names[i] = md.getColumnName(i + 1); vals[i] = rs.getObject(i + 1); }
                return new Object[][] {names, vals};
            }
        }

        private static String insert(String table, String[] cols) {
            return "insert into " + table + " (" + String.join(", ", cols) + ") values (" + String.join(", ", java.util.Collections.nCopies(cols.length, "?")) + ")";
        }

        /** Forge cards the client lacks, single-faced, legal in one of the formats; ordered by format, then name. */
        List<PaperCard> candidates(List<String> formats, int limit, List<String> forced) {
            List<Predicate<PaperCard>> rules = new ArrayList<>();
            for (String f : formats) {
                GameFormat gf = FModel.getFormats().getFormat(f.trim());
                if (gf == null) throw new IllegalArgumentException("Forge has no format " + f);
                rules.add(gf.getFilterRules());
            }
            List<PaperCard> out = new ArrayList<>();
            for (String n : forced) {
                PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(n.trim());
                if (pc == null) System.out.println("[carddb] Forge has no card " + n);
                else if (supported(pc)) out.add(pc);
                else System.out.println("[carddb] skipping " + n + ": already on the client or not single-faced");
            }
            Map<PaperCard, Integer> rank = new HashMap<>();
            for (PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                if (out.contains(pc) || !supported(pc)) continue;
                for (int i = 0; i < rules.size(); i++) {
                    if (rules.get(i).test(pc)) { rank.put(pc, i); break; }
                }
            }
            List<PaperCard> ranked = new ArrayList<>(rank.keySet());
            ranked.sort((a, b) -> rank.get(a) != rank.get(b).intValue() ? rank.get(a) - rank.get(b) : a.getName().compareTo(b.getName()));
            for (PaperCard pc : ranked) {
                if (limit > 0 && out.size() >= limit) break;
                out.add(pc);
            }
            int[] perFormat = new int[rules.size()];
            for (PaperCard pc : out) if (rank.containsKey(pc)) perFormat[rank.get(pc)]++;
            System.out.println("[carddb] candidates: " + ranked.size() + " (by first format " + formats + ": " + Arrays.toString(perFormat) + " taken), forced " + forced.size());
            out.replaceAll(Builder::firstPrinting);
            return out;
        }

        /** The card's first release (not a reprint/promo set such as The List): its set code and collector number go in the row. */
        static PaperCard firstPrinting(PaperCard pc) {
            List<PaperCard> all = StaticData.instance().getCommonCards().getAllCardsNoAlt(pc.getName(),
                    forge.card.CardDb.EDITION_NON_REPRINT.and(forge.card.CardDb.EDITION_NON_PROMO));
            if (all.isEmpty()) all = StaticData.instance().getCommonCards().getAllCardsNoAlt(pc.getName(), forge.card.CardDb.EDITION_NON_REPRINT);
            if (all.isEmpty()) return pc;
            return java.util.Collections.min(all, java.util.Comparator.comparing((PaperCard p) -> {
                forge.card.CardEdition ed = StaticData.instance().getCardEdition(p.getEdition());
                return ed == null || ed.getDate() == null ? new java.util.Date(Long.MAX_VALUE) : ed.getDate();
            }));
        }

        /** Multi-face layouts generated, with the LinkedFaceType each row stores (= the type of the face it links to). */
        static final Map<CardSplitType, int[]> LINKS = Map.of(
                CardSplitType.Transform, new int[] {2, 1},     // DFC: front -> DFC_Back, back -> DFC_Front
                CardSplitType.Modal, new int[] {10, 9},        // MDFC
                CardSplitType.Flip, new int[] {2, 1},          // the client has no flip layout: shown as a transforming DFC
                CardSplitType.Adventure, new int[] {8, 7},     // parent -> AdventureChild, child -> AdventureParent
                CardSplitType.Omen, new int[] {18, 17},
                CardSplitType.Prepare, new int[] {19, 20},     // parent -> PreparedSpell, spell -> PreparedSpellParent
                CardSplitType.Split, new int[] {6, 5});        // split card -> SplitHalf, halves -> SplitCard (Rooms: 16 / 15)

        private boolean supported(PaperCard pc) {
            CardRules r = pc.getRules();
            CardType t = r.getType();
            boolean wanted = nonPrimaryOnly ? existingNames.contains(pc.getName()) && !primaryNames.contains(pc.getName()) : !existingNames.contains(pc.getName());
            return wanted && (r.getSplitType() == CardSplitType.None || LINKS.containsKey(r.getSplitType()))
                    && !t.isBasicLand() && !t.isPlane() && !t.isPhenomenon() && !t.isScheme() && !t.isVanguard() && !t.isConspiracy()
                    && !t.isDungeon() && !pc.getName().startsWith("A-");
        }

        /**
         * Rows for what Forge shows in the command zone without a card behind it, when the client has no row of that name (it
         * has The Monarch and the AFR dungeons): the Initiative designation and Undercity, its dungeon (Vintage's Initiative
         * decks), and the schemes of Archenemy: Sparky. Not primary: never in the collection or a deck. Returns the number of rows added.
         */
        int addGamePieces() throws Exception {
            List<CardRules> pieces = new ArrayList<>();
            pieces.add(CardRules.fromScript(List.of("Name:The Initiative", "ManaCost:no cost", "Types:",
                    "Oracle:Whenever one or more creatures a player controls deal combat damage to you, that player takes the initiative.\n"
                            + "Whenever you take the initiative and at the beginning of your upkeep, venture into Undercity. (If you're in a dungeon, "
                            + "advance to the next room. If not, enter Undercity. You can take the initiative even if you already have it.)")));
            forge.item.PaperToken undercity = StaticData.instance().getAllTokens().getToken("undercity");
            if (undercity != null) pieces.add(undercity.getRules());
            // Archenemy: Sparky's schemes; the client draws a scheme that stays face up as an emblem of its row (StateSync)
            for (String scheme : forge.mtga.Archenemy.schemeNames()) {
                PaperCard pc = StaticData.instance().getVariantCards().getUniqueByName(scheme);
                if (pc == null) System.out.println("[carddb] Forge has no scheme " + scheme);
                else pieces.add(pc.getRules());
            }
            // Momir Basic: the avatar in the command zone is drawn as an emblem of this row (only its ability, not the hand/life line)
            pieces.add(CardRules.fromScript(List.of("Name:" + forge.mtga.Archenemy.MOMIR_AVATAR, "ManaCost:no cost", "Types:Vanguard",
                    "Oracle:{X}, Discard a card: Create a token that's a copy of a creature card with mana value X chosen at random. "
                            + "Activate only as a sorcery and only once each turn.")));
            int n = 0;
            for (CardRules r : pieces) {
                if (existingNames.contains(r.getName())) continue;
                PaperCard pc = new PaperCard(r, "CLB", forge.card.CardRarity.Special);
                Map<String, Object> v = face(pc, r.getMainPart(), r.getName(), nextGrp++, nextArt++, false);
                if (r.getType().isDungeon()) dungeonFrame(v, r.getMainPart());
                insert(v);
                n++;
            }
            return n;
        }

        /**
         * Experimental: draw a generated dungeon (Undercity) with the AFR dungeon frame, as the client does for its own three. That frame's
         * textbox (CDCPart_TextBox_Dungeon) fills its room slots from the card's abilities in order, splitting each at the em dash into
         * room name and effect, so only the room abilities are kept: lines without a dash ("You can't enter this dungeon unless...") are
         * dropped. Which room layout the client picks for an unknown dungeon is up to its asset lookup, not visible in code.
         */
        private static void dungeonFrame(Map<String, Object> v, ICardFace face) {
            v.put("RawFrameDetail", "Dungeon");
            v.put("AdditionalFrameDetails", "dungeon");
            String oracle = face.getOracleText() == null ? "" : face.getOracleText().replace("\\n", "\n");
            List<String> lines = new ArrayList<>();
            for (String line : oracle.split("\n")) if (!line.isBlank()) lines.add(line.trim());
            String[] refs = ((String) v.get("AbilityIds")).split(",");
            if (refs.length != lines.size()) return;        // one ref per oracle line, or the order is unknown: keep them all
            List<String> rooms = new ArrayList<>();
            for (int i = 0; i < refs.length; i++) if (lines.get(i).contains("—")) rooms.add(refs[i]);
            v.put("AbilityIds", String.join(",", rooms));
        }

        void add(PaperCard pc) throws Exception {
            CardRules r = pc.getRules();
            ICardFace main = r.getMainPart();
            if (r.getSplitType() == CardSplitType.None) {
                insert(face(pc, main, main.getName(), nextGrp++, nextArt++, true));
                return;
            }
            ICardFace other = r.getOtherPart();
            int[] link = LINKS.get(r.getSplitType());
            if (r.getSplitType() == CardSplitType.Split) {
                boolean room = main.getType().hasSubtype("Room");
                int parent = nextGrp++, left = nextGrp++, right = nextGrp++, art = nextArt++, leftArt = nextArt++, rightArt = nextArt++;
                // the card shows Scryfall's whole art crop (both arts side by side); each half has an art id of its own, the art server cuts its half out
                Map<String, Object> l = face(pc, main, main.getName(), left, leftArt, false);
                Map<String, Object> rt = face(pc, other, other.getName(), right, rightArt, false);
                for (Map<String, Object> half : List.of(l, rt)) {
                    half.put("LinkedFaceType", room ? 15 : link[1]);
                    half.put("LinkedFaceGrpIds", Integer.toString(parent));
                }
                Map<String, Object> p = face(pc, main, main.getName() + (room ? " // " : " /// ") + other.getName(), parent, art, true);
                p.put("LinkedFaceType", room ? 16 : link[0]);
                p.put("LinkedFaceGrpIds", left + "," + right);
                // the split card itself shows both halves: union of types, both costs, all colors, both halves' abilities
                List<String> core = new ArrayList<>(), sub = new ArrayList<>(), sup = new ArrayList<>();
                for (CardType t : List.of(main.getType(), other.getType())) {
                    t.getCoreTypes().forEach(c -> { if (!core.contains(c.name())) core.add(c.name()); });
                    t.getSubtypes().forEach(c -> { if (!sub.contains(c)) sub.add(c); });
                    t.getSupertypes().forEach(c -> { if (!sup.contains(c.name())) sup.add(c.name()); });
                }
                p.put("Types", enums(core, cardTypes));
                p.put("Subtypes", enums(sub, subTypes));
                p.put("Supertypes", enums(sup, superTypes));
                p.put("TypeTextId", loc((String.join(" ", sup) + " " + String.join(" ", core)).trim()));
                p.put("SubtypeTextId", sub.isEmpty() ? 0 : loc(String.join(" ", sub)));
                p.put("OldSchoolManaText", (String) l.get("OldSchoolManaText") + rt.get("OldSchoolManaText"));
                p.put("Colors", colors(r.getColor()));
                p.put("FrameColors", colors(r.getColor()));
                p.put("AbilityIds", join((String) l.get("AbilityIds"), (String) rt.get("AbilityIds")));
                // Fuse (ability 103) belongs to the card itself: the client draws it as a bar under both halves and, for a card with it,
                // shows nothing but that ability in the text box (FormatFuseAbilities), so a half carrying it would lose its own rules text
                for (Map<String, Object> half : List.of(l, rt)) half.put("AbilityIds", withoutFuse((String) half.get("AbilityIds")));
                // every Aftermath card of the client (ability 159) has the "aftermath" frame detail on the card and both halves: it picks the rotated half frame
                if (("," + p.get("AbilityIds")).contains(",159:")) for (Map<String, Object> row : List.of(p, l, rt)) row.put("AdditionalFrameDetails", "aftermath");
                p.put("Order_CMCWithXLast", main.getManaCost().getCMC() + other.getManaCost().getCMC());
                insert(p);
                insert(l);
                insert(rt);
                return;
            }
            // two faces: the front/parent is the collectible card, the back/child only exists linked to it
            boolean dfc = r.getSplitType() == CardSplitType.Transform || r.getSplitType() == CardSplitType.Modal
                    || r.getSplitType() == CardSplitType.Flip;
            int front = nextGrp++, back = nextGrp++, frontArt = nextArt++;
            Map<String, Object> f = face(pc, main, main.getName(), front, frontArt, true);
            Map<String, Object> b = face(pc, other, other.getName(), back, dfc ? nextArt++ : frontArt, false);
            f.put("LinkedFaceType", link[0]);
            f.put("LinkedFaceGrpIds", Integer.toString(back));
            b.put("LinkedFaceType", link[1]);
            b.put("LinkedFaceGrpIds", Integer.toString(front));
            if (dfc) {
                f.put("CollectorSuffix", "a");
                b.put("CollectorSuffix", "b");
                if (r.getSplitType() == CardSplitType.Transform) b.put("OldSchoolManaText", "");   // a transformed face has no cost
            }
            insert(f);
            insert(b);
        }

        private static String withoutFuse(String refs) {
            return Arrays.stream(refs.split(",")).filter(s -> !s.isEmpty() && !s.startsWith("103:")).collect(java.util.stream.Collectors.joining(","));
        }

        /** Both halves' ability refs, each once (fuse etc. sit on both halves). */
        private static String join(String a, String b) {
            java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
            for (String s : (a + "," + b).split(",")) if (!s.isEmpty()) refs.add(s);
            return String.join(",", refs);
        }

        private void insert(Map<String, Object> v) throws Exception {
            for (String k : v.keySet()) {
                if (Arrays.asList(cardCols).indexOf(k) < 0) throw new IllegalStateException("Cards has no column " + k);
            }
            fill(insCard, cardCols, cardTemplate, v);
            insCard.executeUpdate();
        }

        /** Cards row values for one face; the title is the face name (a split card's own row: "A /// B"). */
        private Map<String, Object> face(PaperCard pc, ICardFace face, String title, int grp, int art, boolean primary) throws Exception {
            CardType type = face.getType();
            String[] typeLine = type.toString().split("\\s+-\\s+", 2);

            List<String> abilityRefs = new ArrayList<>();
            String oracle = face.getOracleText() == null ? "" : face.getOracleText().replace("\\n", "\n");
            boolean spell = type.isInstant() || type.isSorcery();
            for (String line : oracle.split("\n")) {
                if (!line.isBlank()) abilityRefs.addAll(abilities(line.trim(), face.getName(), spell));
            }

            Map<String, Object> v = new HashMap<>();
            v.put("GrpId", grp);
            v.put("ArtId", art);
            v.put("ArtPath", "");
            v.put("TitleId", newLoc(title));
            v.put("AltTitleId", 0);
            v.put("InterchangeableTitleId", 0);
            v.put("FlavorTextId", 0);
            v.put("ReminderTextId", 0);
            v.put("TypeTextId", loc(typeLine[0]));
            v.put("SubtypeTextId", typeLine.length > 1 ? loc(typeLine[1]) : 0);
            v.put("ArtistCredit", "");
            v.put("ArtSize", 0);
            v.put("Rarity", rarity(pc.getRarity()));
            v.put("ExpansionCode", pc.getEdition());
            v.put("DigitalReleaseSet", "");
            v.put("IsToken", 0);
            v.put("IsPrimaryCard", primary ? 1 : 0);
            v.put("IsDigitalOnly", 0);
            v.put("IsRebalanced", 0);
            v.put("RebalancedCardGrpId", 0);
            v.put("DefunctRebalancedCardGrpId", 0);
            v.put("AlternateDeckLimit", 0);
            v.put("CollectorNumber", pc.getCollectorNumber() == null ? "" : pc.getCollectorNumber());
            v.put("CollectorMax", "");
            v.put("CollectorSuffix", "");
            v.put("OldSchoolManaText", manaText(face.getManaCost() == null ? "" : face.getManaCost().toString()));
            v.put("LinkedFaceType", 0);
            v.put("RawFrameDetail", "");
            v.put("Watermark", "");
            v.put("TextChangeData", "");
            String power = "", toughness = "";
            if (type.isCreature()) { power = nz(face.getPower()); toughness = nz(face.getToughness()); }
            else if (type.isPlaneswalker()) toughness = nz(face.getInitialLoyalty());
            else if (type.isBattle()) toughness = nz(face.getDefense());
            v.put("Power", power);
            v.put("Toughness", toughness);
            String colors = colors(face.getColor());
            v.put("Colors", colors);
            v.put("ColorIdentity", colors(pc.getRules().getColorIdentity()));
            v.put("FrameColors", colors);
            v.put("IndicatorColors", "");
            v.put("Types", enums(type.getCoreTypes().stream().map(t -> t.name()).toList(), cardTypes));
            v.put("Subtypes", enums(type.getSubtypes(), subTypes));
            v.put("Supertypes", enums(type.getSupertypes().stream().map(t -> t.name()).toList(), superTypes));
            v.put("AbilityIds", String.join(",", abilityRefs));
            v.put("HiddenAbilityIds", "");
            v.put("LinkedFaceGrpIds", "");
            v.put("LinkedAbilityTemplateCardGrpIds", "");
            v.put("AbilityIdToLinkedTokenGrpId", "");
            v.put("AbilityIdToLinkedConjurations", "");
            v.put("KnownSupportedStyles", "");
            v.put("AdditionalFrameDetails", "");
            v.put("ExtraFrameDetails", "");
            v.put("Tags", "");
            v.put("PromoLabel", "");
            // deck builder sort keys
            int cmc = face.getManaCost() == null ? 0 : face.getManaCost().getCMC();
            v.put("Order_LandLast", type.isLand() ? 1 : 0);
            v.put("Order_ColorOrder", colorOrder(face.getColor()));
            v.put("Order_CreaturesFirst", type.isCreature() ? 0 : 1);
            v.put("Order_ManaCostDifficulty", face.getColor().countColors());
            v.put("Order_CMCWithXLast", cmc);
            v.put("Order_Title", title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            v.put("Order_MythicToCommon", 5 - rarity(pc.getRarity()));
            v.put("Order_BasicLandsFirst", 1);
            return v;
        }

        /**
         * "abilityId:textId" refs for one oracle line: existing client abilities when the text matches, else a new one.
         * The ability's own text keeps CARDNAME; the textId in the ref is what the card displays, with the name filled in
         * (as on Arena rows: Bolt's ability says "CARDNAME deals 3 damage...", the card's ref text names the card).
         */
        private List<String> abilities(String line, String cardName, boolean spell) throws Exception {
            String text = arenaText(line, cardName);
            Integer known = abilityByText.get(text);
            if (known != null) {
                reusedAbilities++;
                return List.of(known + ":" + cardText(text, cardName, abilityText.get(known)));
            }
            // keyword lines: "Flying, vigilance", "Equip {1}" ... each part a known ability
            String bare = text.replaceAll("\\s*\\([^)]*\\)", "").trim();
            if (!bare.isEmpty() && !bare.contains(".")) {
                List<String> refs = new ArrayList<>();
                for (String part : bare.split(",\\s*")) {
                    String p = part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1);
                    Integer id = abilityByText.get(p);
                    if (id == null) { refs = null; break; }
                    refs.add(id + ":" + cardText(p, cardName, abilityText.get(id)));
                }
                if (refs != null && !refs.isEmpty()) { reusedAbilities += refs.size(); return refs; }
            }
            String loyalty = "";
            int category;
            Matcher lm = LOYALTY.matcher(text);
            if (lm.matches()) {
                loyalty = lm.group(1).replace('−', '-');
                text = lm.group(2);
                category = 1;
            } else if (spell) {
                category = 4;
            } else if (text.matches("^(When|Whenever|At) .*")) {
                category = 2;
            } else if (text.matches("^[^.\"]*:\\s.*") && !text.startsWith("CARDNAME")) {
                category = 1;
            } else {
                category = 3;
            }
            int id = nextAbility++, textId = newLoc(text);
            Map<String, Object> v = new HashMap<>();
            v.put("Id", id);
            v.put("BaseId", 0);
            v.put("TextId", textId);
            v.put("Category", category);
            v.put("SubCategory", category == 1 && text.matches(".*: Add \\{.*") ? 1 : 0);
            // id-list columns are NULL when empty: the client UInt32.Parse()s every comma part of a non-NULL value
            v.put("LoyaltyCost", loyalty.isEmpty() ? null : loyalty);
            for (String col : new String[] {"ModalChildIds", "ModalParentIds", "HiddenAbilityIds", "HiddenByAbilityIds",
                    "EmbeddedActiveAbilityIds", "EmbeddedIntoAbilityIds", "ReferencedAbilityIds"}) v.put(col, null);
            fill(insAbility, abilityCols, abilityTemplate, v);
            insAbility.executeUpdate();
            newAbilities++;
            return List.of(id + ":" + cardText(text, cardName, textId));
        }

        /** The card's own text for an ability: CARDNAME replaced by the face name, else the ability's text. */
        private int cardText(String text, String cardName, int abilityTextId) throws Exception {
            return text.contains("CARDNAME") ? loc(text.replace("CARDNAME", cardName)) : abilityTextId;
        }

        private static void fill(PreparedStatement ps, String[] cols, Object[] template, Map<String, Object> v) throws Exception {
            for (int i = 0; i < cols.length; i++) ps.setObject(i + 1, v.containsKey(cols[i]) ? v.get(cols[i]) : template[i]);
        }

        private int loc(String text) throws Exception {
            Integer id = locByText.get(text);
            return id != null ? id : newLoc(text);
        }

        private int newLoc(String text) throws Exception {
            int id = nextLoc++;
            insLocId.setInt(1, id);
            insLocId.executeUpdate();
            for (PreparedStatement ps : insLoc.values()) {
                ps.setInt(1, id);
                ps.setString(2, text);
                ps.executeUpdate();
            }
            locByText.putIfAbsent(text, id);
            return id;
        }

        private String enums(java.util.Collection<String> names, Map<String, Integer> map) {
            List<String> out = new ArrayList<>();
            for (String n : names) {
                Integer v = map.get(n);
                if (v == null && n.equals("Tribal")) v = map.get("Kindred");
                if (v != null) out.add(v.toString());
                else if (map == subTypes) unmappedSubtypes.add(n);
            }
            return String.join(",", out);
        }
    }

    /** Forge oracle line -> client ability text: own name as CARDNAME, symbol runs as one "{o..}" group. */
    static String arenaText(String line, String cardName) {
        String t = line.replace(cardName, "CARDNAME").replace('’', '\'');
        Matcher m = SYMBOL_RUN.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("{" + manaText(m.group()) + "}"));
        m.appendTail(sb);
        return sb.toString();
    }

    /** "{2}{B}{R/G}{U/P}" -> "o2oBo(R/G)o(U/P)". */
    static String manaText(String forge) {
        StringBuilder sb = new StringBuilder();
        Matcher m = SYMBOL.matcher(forge);
        while (m.find()) {
            String s = m.group(1);
            sb.append(s.contains("/") ? "o(" + s + ")" : "o" + s);
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String colors(ColorSet cs) {
        List<String> out = new ArrayList<>();
        if (cs.hasWhite()) out.add("1");
        if (cs.hasBlue()) out.add("2");
        if (cs.hasBlack()) out.add("3");
        if (cs.hasRed()) out.add("4");
        if (cs.hasGreen()) out.add("5");
        return String.join(",", out);
    }

    private static int colorOrder(ColorSet cs) {
        int n = cs.countColors();
        if (n == 0) return 6;
        if (n > 1) return 5;
        return cs.hasWhite() ? 0 : cs.hasBlue() ? 1 : cs.hasBlack() ? 2 : cs.hasRed() ? 3 : 4;
    }

    private static int rarity(CardRarity r) {
        return switch (r) {
            case BasicLand -> 1;
            case Common -> 2;
            case Uncommon -> 3;
            case Rare, Special -> 4;
            case MythicRare -> 5;
            default -> 2;
        };
    }
}
