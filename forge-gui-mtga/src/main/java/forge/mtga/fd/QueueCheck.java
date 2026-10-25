package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
        int queues = PlayQueues.Mode.values().length * PlayQueues.FORMATS.size();
        check(cfg.size() == queues && events.size() == queues, queues + " queues and " + queues + " events");
        for (int i = 0; i < cfg.size(); i++) {
            JsonObject c = cfg.get(i).getAsJsonObject(), e = events.get(i).getAsJsonObject();
            String name = e.get("InternalEventName").getAsString();
            check(c.get("EventNameBO1").getAsString().equals(name) && PlayQueues.isQueueEvent(name), "queue " + c.get("Id").getAsString() + " -> event " + name);
            String format = e.getAsJsonObject("EventUXInfo").get("DeckSelectFormat").getAsString();
            check(format.equals(PlayQueues.formatOf(name)) && PlayQueues.FORMATS.contains(format), name + " deck picker format " + format);
            check("Queue".equals(e.getAsJsonObject("EventUXInfo").get("EventBladeBehavior").getAsString()), name + " is a queue event");
            boolean ranked = PlayQueues.isRanked(name), flagged = e.getAsJsonArray("Flags").toString().contains("\"Ranked\"");
            check(ranked == "Ranked".equals(c.get("QueueType").getAsString()) && ranked == flagged, name + (ranked ? " is in the Ranked list with the Ranked flag" : " is unranked"));
        }
        check(PlayQueues.modeOf("BotPlay_Modern") == PlayQueues.Mode.BOT && PlayQueues.modeOf("CasualPlay_Pioneer") == PlayQueues.Mode.CASUAL
                && PlayQueues.modeOf("BotPlay_Pauper") == PlayQueues.Mode.BOT && PlayQueues.modeOf("RankedPlay_Vintage") == PlayQueues.Mode.RANKED && PlayQueues.modeOf("BotPlay_Brawl") == null && PlayQueues.modeOf("QuickDraft_MID") == null && PlayQueues.modeOf("AIBotMatch") == null,
                "event names parse");

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
