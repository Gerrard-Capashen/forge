package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.model.FModel;
import forge.mtga.forge.HeadlessGui;
import forge.mtga.forge.CardDb;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headless check of the play queues (Bot Play / Casual Play x every queue format): the queue config and event list the
 * client sees, and the server's deck-format enforcement.
 * {@code java forge.mtga.fd.QueueCheck <client folder>} from forge-gui; prints QUEUE CHECK OK / FAILED.
 */
public final class QueueCheck {
    private static int failed;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }

    private static Map<String, Integer> deck(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    public static void main(String[] args) throws Exception {
        forge.gui.GuiBase.setInterface(new HeadlessGui(new File(".")));
        FModel.initialize(null, null);
        CardDb db = new CardDb(CardDb.findDatabase(new File(args.length > 0 ? args[0] : "../../client")));
        ForgeFormats formats = new ForgeFormats(db);
        PlayQueues q = new PlayQueues();

        JsonArray cfg = q.queueConfig(), events = q.eventInfos();
        int queues = 0, bo3s = PlayQueues.FORMATS.size();       // Bot Play offers a best-of-three event for every format but Brawl
        for (PlayQueues.Mode m : PlayQueues.Mode.values()) queues += PlayQueues.formats(m).size();
        // Bot Play also has a best-of-three event per format
        check(cfg.size() == queues && events.size() == queues + bo3s, queues + " queues and " + (queues + bo3s) + " events");
        java.util.Map<String, JsonObject> byName = new java.util.HashMap<>();
        for (int i = 0; i < events.size(); i++) byName.put(events.get(i).getAsJsonObject().get("InternalEventName").getAsString(), events.get(i).getAsJsonObject());
        for (int i = 0; i < cfg.size(); i++) {
            JsonObject c = cfg.get(i).getAsJsonObject(), e = byName.get(cfg.get(i).getAsJsonObject().get("EventNameBO1").getAsString());
            String name = e.get("InternalEventName").getAsString();
            boolean bot = PlayQueues.modeOf(name) == PlayQueues.Mode.BOT && !PlayQueues.BRAWL.equals(PlayQueues.formatOf(name));
            String bo3 = c.get("EventNameBO3").isJsonNull() ? null : c.get("EventNameBO3").getAsString();
            check(bot ? (name + PlayQueues.BO3).equals(bo3) : bo3 == null, name + (bot ? " offers a best-of-three event" : " is best-of-one only"));
            if (bot) {
                JsonObject e3 = byName.get(bo3);
                check(e3 != null && PlayQueues.isQueueEvent(bo3) && PlayQueues.modeOf(bo3) == PlayQueues.Mode.BOT && PlayQueues.formatOf(bo3).equals(PlayQueues.formatOf(name))
                        && "BestOf3".equals(e3.get("WinCondition").getAsString()) && PlayQueues.gamesToWin(bo3) == 2 && PlayQueues.gamesToWin(name) == 1,
                        bo3 + " is a best-of-three queue event of the same format");
            }
            check(c.get("EventNameBO1").getAsString().equals(name) && PlayQueues.isQueueEvent(name), "queue " + c.get("Id").getAsString() + " -> event " + name);
            String format = e.getAsJsonObject("EventUXInfo").get("DeckSelectFormat").getAsString();
            check(format.equals(PlayQueues.formatOf(name)) && PlayQueues.formats(PlayQueues.modeOf(name)).contains(format), name + " deck picker format " + format);
            check("Queue".equals(e.getAsJsonObject("EventUXInfo").get("EventBladeBehavior").getAsString()), name + " is a queue event");
            boolean ranked = PlayQueues.isRanked(name), flagged = e.getAsJsonArray("Flags").toString().contains("\"Ranked\"");
            check(ranked == "Ranked".equals(c.get("QueueType").getAsString()) && ranked == flagged, name + (ranked ? " is in the Ranked list with the Ranked flag" : " is unranked"));
        }
        check(PlayQueues.modeOf("BotPlay_Modern") == PlayQueues.Mode.BOT && PlayQueues.modeOf("CasualPlay_Pioneer") == PlayQueues.Mode.CASUAL
                && PlayQueues.modeOf("BotPlay_Pauper") == PlayQueues.Mode.BOT && PlayQueues.modeOf("RankedPlay_Vintage") == PlayQueues.Mode.RANKED && PlayQueues.modeOf("BotPlay_Brawl") == PlayQueues.Mode.BOT && PlayQueues.modeOf("CasualPlay_Brawl") == PlayQueues.Mode.CASUAL
                && PlayQueues.modeOf("RankedPlay_Brawl") == null && PlayQueues.modeOf("BotPlay_Brawl_BO3") == null && PlayQueues.modeOf("QuickDraft_MID") == null && PlayQueues.modeOf("AIBotMatch") == null,
                "event names parse");

        // Archenemy: Sparky is an event page, not a queue: three stages, each with a complete deck and schemes that exist in Forge and on the client
        check(!PlayQueues.isQueueEvent(ArchenemyEvent.NAME) && ArchenemyEvent.is("Archenemy_Sparky"), "Archenemy_Sparky is an event of its own, not a queue");
        JsonObject info = new ArchenemyEvent(new File(System.getProperty("java.io.tmpdir"), "queuecheck-arch-" + System.nanoTime())).eventInfos().get(0).getAsJsonObject();
        check("EventPage".equals(info.getAsJsonObject("EventUXInfo").get("EventBladeBehavior").getAsString())
                && info.getAsJsonObject("EventUXInfo").getAsJsonObject("EventComponentData").getAsJsonObject("ByCourseObjectiveTrack").getAsJsonArray("ChestDescriptions").size() == 4, "the event page has a win track of three stages");
        for (forge.mtga.Archenemy.Stage stage : forge.mtga.Archenemy.STAGES) {
            forge.mtga.AiDecks.Choice sparky = forge.mtga.Archenemy.deck(stage, n -> db.grpId(n) != 0);
            check(sparky.cards().values().stream().mapToInt(Integer::intValue).sum() == 99 && sparky.commanders().size() == 1, stage.name() + " plays 99 cards and a commander");
            long lost = sparky.cards().keySet().stream().filter(n -> db.grpId(n) == 0 || StaticData.instance().getCommonCards().getUniqueByName(n) == null).count();
            check(lost == 0, "every card of " + stage.name() + "'s deck is known to Forge and the client");
            check(sparky.schemes().size() >= 20, stage.name() + "'s scheme deck has " + sparky.schemes().size() + " schemes");
        }
        for (String scheme : forge.mtga.Archenemy.schemeNames()) {
            check(StaticData.instance().getVariantCards().getUniqueByName(scheme) != null, "Forge has the scheme " + scheme);
            check(db.grpId(scheme) != 0, "the client card DB has a row for the scheme " + scheme + " (rebuild it with CardDbBuilder if not)");
        }

        // Brawl: its queues sit under the blade's Brawl tab (QueueType "Brawl"), best of one; a generated AI deck passes the server's own check
        for (String id : new String[] {"bot-brawl", "casual-brawl"}) {
            JsonObject b = null;
            for (int i = 0; i < cfg.size(); i++) if (id.equals(cfg.get(i).getAsJsonObject().get("Id").getAsString())) b = cfg.get(i).getAsJsonObject();
            check(b != null && "Brawl".equals(b.get("QueueType").getAsString()) && b.get("EventNameBO3").isJsonNull(), id + " is a best-of-one queue of the Brawl tab");
        }
        forge.mtga.AiDecks.Choice brawl = forge.mtga.AiDecks.brawl(n -> db.grpId(n) != 0);
        int brawlCards = brawl.cards().values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("  AI Brawl deck: " + brawl.name() + ", " + brawlCards + " cards + " + brawl.commanders());
        check(brawl.commanders().size() == 1 && brawlCards == 99, "the AI's Brawl deck is 99 cards and a commander");
        check(formats.problem("Commander", brawl.cards(), brawl.commanders(), 0) == null, "a Brawl deck is a Commander deck too (same pool and bans)");
        check(formats.problem("Brawl", brawl.cards(), brawl.commanders(), 0) == null, "Brawl accepts the generated deck: " + formats.problem("Brawl", brawl.cards(), brawl.commanders(), 0));
        check(formats.problem("Brawl", brawl.cards(), java.util.List.of(), 0) != null, "Brawl refuses a deck without a commander");
        check(formats.problem("Standard", deck("Mountain", 60), brawl.commanders(), 0) != null, "Standard refuses a commander");

        Map<String, Integer> bolt = deck("Lightning Bolt", 4, "Mountain", 56);
        check(formats.problem("Modern", bolt, 0) == null, "Modern accepts Bolt + Mountain");
        check(formats.problem("Pioneer", bolt, 0) != null, "Pioneer refuses Lightning Bolt: " + formats.problem("Pioneer", bolt, 0));
        check(formats.problem("Standard", bolt, 0) != null, "Standard refuses Lightning Bolt");
        check(formats.problem("Modern", deck("Black Lotus", 1, "Mountain", 59), 0) != null, "Modern refuses Black Lotus");
        check(formats.problem("Modern", deck("Lightning Bolt", 4, "Mountain", 55), 0) != null, "Modern refuses 59 cards");
        check(formats.problem("Modern", deck("Lightning Bolt", 5, "Mountain", 55), 0) != null, "Modern refuses 5 Bolts");
        check(formats.problem("Modern", bolt, 1) != null, "a card Forge does not know is refused");
        check(formats.problem("Modern", deck("Relentless Rats", 30, "Mountain", 30), 0) == null, "any number of Relentless Rats is fine");
        check(formats.problem("Legacy", bolt, 0) == null, "Legacy accepts Bolt + Mountain");
        check(formats.problem("Pauper", bolt, 0) == null, "Pauper accepts Bolt + Mountain");
        check(formats.problem("Premodern", bolt, 0) == null, "Premodern accepts Bolt + Mountain");
        check(formats.problem("Premodern", deck("Arcbound Ravager", 4, "Mountain", 56), 0) != null, "Premodern refuses Arcbound Ravager (Darksteel): " + formats.problem("Premodern", deck("Arcbound Ravager", 4, "Mountain", 56), 0));
        check(formats.problem("Premodern", deck("Necropotence", 1, "Swamp", 59), 0) != null, "Premodern bans Necropotence");
        check(formats.problem("Premodern", deck("Hermit Druid", 4, "Forest", 56), 0) == null, "Premodern allows Hermit Druid (banned in Legacy)");
        check(formats.problem("Premodern", deck("Black Lotus", 1, "Mountain", 59), 0) != null, "Premodern refuses Black Lotus");
        check(formats.problem("Premodern", deck("Thoughtseize", 4, "Swamp", 56), 0) != null, "Premodern refuses Thoughtseize");
        check(formats.problem("Legacy", deck("Black Lotus", 1, "Mountain", 59), 0) != null, "Legacy refuses Black Lotus");
        check(formats.problem("Vintage", deck("Black Lotus", 1, "Mountain", 59), 0) == null, "Vintage accepts one Black Lotus");
        check(formats.problem("Vintage", deck("Brainstorm", 2, "Mountain", 58), 0) != null, "Vintage refuses 2 Brainstorm (restricted): " + formats.problem("Vintage", deck("Brainstorm", 2, "Mountain", 58), 0));
        check(formats.problem("Pauper", deck("Thoughtseize", 4, "Swamp", 56), 0) != null, "Pauper refuses Thoughtseize: " + formats.problem("Pauper", deck("Thoughtseize", 4, "Swamp", 56), 0));

        // built-in decks: every list must be playable in its own format, with every card on the client
        for (forge.mtga.DefaultDecks.Entry d : forge.mtga.DefaultDecks.all()) {
            int total = d.main().values().stream().mapToInt(Integer::intValue).sum();
            java.util.List<String> lacking = new java.util.ArrayList<>();
            for (String n : d.main().keySet()) if (db.grpId(n) == 0) lacking.add(n);
            String problem = formats.problem(d.format(), d.main(), 0);
            System.out.println("  deck " + d.format() + " / " + d.name() + ": " + total + " cards, side " + d.side().values().stream().mapToInt(Integer::intValue).sum()
                    + (lacking.isEmpty() ? "" : ", NOT ON CLIENT " + lacking) + (problem == null ? "" : ", ILLEGAL: " + problem));
            check(lacking.isEmpty() && problem == null, "built-in deck " + d.format() + " / " + d.name());
        }

        // the bots' wider pool (resources bot-decks): lists the client lacks cards for or the format refuses are skipped at play time; each format keeps a wide choice
        for (String f : PlayQueues.FORMATS) {
            java.util.List<forge.mtga.DefaultDecks.Entry> pool = forge.mtga.DefaultDecks.botPool(f);
            int ok = 0;
            for (forge.mtga.DefaultDecks.Entry d : pool) {
                java.util.List<String> lacking = new java.util.ArrayList<>();
                for (String n : d.main().keySet()) if (db.grpId(n) == 0) lacking.add(n);
                String problem = formats.problem(d.format(), d.main(), 0);
                if (lacking.isEmpty() && problem == null) ok++;
                else System.out.println("  bot pool " + f + " / " + d.name() + " skipped:" + (lacking.isEmpty() ? "" : " NOT ON CLIENT " + lacking) + (problem == null ? "" : " ILLEGAL: " + problem));
            }
            System.out.println("  bot pool " + f + ": " + ok + " playable of " + pool.size());
            check(ok >= (f.equals("Premodern") ? 6 : 8), "bot pool " + f + " has " + ok + " playable decks");   // Premodern: the six starter decks, no MTGGoldfish pool
        }

        // seeding on a copy of the real data dir (old "Forge: ..." starter decks, the player's own decks)
        File real = new File("../../data"), tmp = java.nio.file.Files.createTempDirectory("queuecheck").toFile();
        if (new File(real, "decks").isDirectory()) {
            for (File f : new File(real, "decks").listFiles()) {
                java.nio.file.Files.createDirectories(new File(tmp, "decks").toPath());
                java.nio.file.Files.copy(f.toPath(), new File(tmp, "decks/" + f.getName()).toPath());
            }
            java.nio.file.Files.writeString(new File(tmp, ".seeded").toPath(), "old");
            FrontDoor fd = new FrontDoor(db, tmp, null);
            java.util.List<String> names = new java.util.ArrayList<>();
            for (JsonObject d : fd.decks().all()) names.add(d.getAsJsonObject("Summary").get("Name").getAsString());
            System.out.println("  decks after seeding: " + names);
            check(names.stream().noneMatch(n -> n.startsWith("Forge: ")), "old untouched starter decks removed");
            check(names.contains("Izzet Prowess") && names.contains("Dimir Midrange"), "the player's own decks are kept");
            long seeded = forge.mtga.DefaultDecks.all().stream().filter(d -> names.contains(d.name() + " (" + d.format() + ")")).count();
            check(seeded >= forge.mtga.DefaultDecks.all().size() - 2, "built-in decks seeded: " + seeded + " of " + forge.mtga.DefaultDecks.all().size());
            for (String f : PlayQueues.FORMATS) {
                forge.mtga.AiDecks.Choice c = forge.mtga.AiDecks.fromDefaults(f, d -> formats.problem(d.format(), d.main(), 0) == null && d.main().keySet().stream().allMatch(n -> db.grpId(n) != 0));
                check(c != null && formats.problem(f, c.cards(), 0) == null, "bot deck for " + f + ": " + (c == null ? null : c.name()));
            }
        }

        // a brand-new profile gets every built-in deck; the ledger keeps a deleted one deleted
        File fresh = java.nio.file.Files.createTempDirectory("queuecheck-new").toFile();
        int all = forge.mtga.DefaultDecks.all().size();
        FrontDoor first = new FrontDoor(db, fresh, null);
        check(first.decks().all().size() == all, "a new profile gets all " + all + " built-in decks (" + first.decks().all().size() + ")");
        JsonObject vintage = first.decks().all().stream().filter(d -> d.getAsJsonObject("Summary").get("Name").getAsString().endsWith("(Vintage)")).findFirst().orElseThrow();
        String deleted = vintage.getAsJsonObject("Summary").get("Name").getAsString();
        first.decks().delete(vintage.getAsJsonObject("Summary").get("DeckId").getAsString());
        FrontDoor second = new FrontDoor(db, fresh, null);
        check(second.decks().all().size() == all - 1 && second.decks().all().stream().noneMatch(d -> deleted.equals(d.getAsJsonObject("Summary").get("Name").getAsString())),
                "a built-in deck the player deleted is not given again (" + deleted + ")");
        // a profile seeded once by the old marker on a client without the Legacy/Vintage/Pauper cards: those come now, deleted older ones do not
        File older = java.nio.file.Files.createTempDirectory("queuecheck-v4").toFile();
        java.nio.file.Files.writeString(new File(older, ".seeded-defaults-v4").toPath(), "built-in decks seeded: 3");
        FrontDoor moved = new FrontDoor(db, older, null);
        java.util.List<String> movedNames = new java.util.ArrayList<>();
        for (JsonObject d : moved.decks().all()) movedNames.add(d.getAsJsonObject("Summary").get("Name").getAsString());
        long lvp = forge.mtga.DefaultDecks.all().stream().filter(d -> java.util.Set.of("Legacy", "Vintage", "Pauper", "Premodern").contains(d.format())).count();
        check(movedNames.size() == lvp && movedNames.stream().allMatch(n -> n.endsWith("(Legacy)") || n.endsWith("(Vintage)") || n.endsWith("(Pauper)") || n.endsWith("(Premodern)")),
                "an old one-shot profile gets the missing Legacy/Vintage/Pauper/Premodern decks (" + movedNames.size() + " of " + lvp + "), not the Standard/Pioneer/Modern ones it deleted");

        // course flow
        check(q.setDeck("BotPlay_Modern", new JsonObject(), null) instanceof FrontDoor.Err, "set deck before join is an error");
        q.join("BotPlay_Modern");
        JsonObject req = new JsonObject();
        req.add("Deck", new JsonObject());
        check(q.setDeck("BotPlay_Modern", req, "nope") instanceof FrontDoor.Err, "a deck problem is returned as an error");
        check(q.setDeck("BotPlay_Modern", req, null) instanceof JsonObject, "a legal deck is stored");
        check("CreateMatch".equals(q.course("BotPlay_Modern").get("CurrentModule").getAsString()), "course ready for pairing");

        System.out.println(failed == 0 ? "QUEUE CHECK OK" : "QUEUE CHECK FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
