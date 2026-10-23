package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.model.FModel;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.HeadlessGui;
import forge.mtga.profile.CosmeticCatalog;
import forge.mtga.profile.FileProfileStore;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static forge.mtga.fd.FrontDoor.obj;

/**
 * Headless check of the premier drafts: the events the client sees, then for each one the whole client flow against
 * {@link PremierDrafts} (join, table queue pushes, ready check, every pick from the pushed packs, pool grant), as the
 * client's HumanDraftPod / TableDraftQueue screens would drive it.
 * {@code java forge.mtga.fd.PremierCheck <client folder>} from forge-gui; prints PREMIER CHECK OK / FAILED.
 */
public final class PremierCheck {
    private static int failed;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("mtga.premier.botPace") == null) System.setProperty("mtga.premier.botPace", "20-80");   // paced bots, fast
        forge.gui.GuiBase.setInterface(new HeadlessGui(new File(".")));
        FModel.initialize(null, null);
        File clientDir = new File(args.length > 0 ? args[0] : "../../client");
        CardDb db = new CardDb(CardDb.findDatabase(clientDir));
        File data = Files.createTempDirectory("premiercheck").toFile();
        DraftEvents events = new DraftEvents(db, data);
        ProfileApi profile = new ProfileApi(new FileProfileStore(data), CosmeticCatalog.load(clientDir));
        PremierDrafts premier = new PremierDrafts(db, events, profile);

        List<PremierDrafts.Spec> specs = premier.specs();
        check(specs.size() == 2, "two premier events: " + specs.stream().map(PremierDrafts.Spec::eventName).toList());
        JsonArray infos = premier.eventInfos();
        check(infos.size() == specs.size(), "event list has them");
        for (PremierDrafts.Spec spec : specs) run(premier, events, db, spec);

        System.out.println(failed == 0 ? "PREMIER CHECK OK" : "PREMIER CHECK FAILED (" + failed + ")");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(PremierDrafts premier, DraftEvents events, CardDb db, PremierDrafts.Spec spec) throws Exception {
        String name = spec.eventName();
        System.out.println(name + " (" + spec.title() + ", " + spec.poolGrpIds().size() + " cards on the client):");
        Object joined = premier.join(obj("EventName", name));
        check(joined instanceof JsonObject j && "PlayerDraft".equals(j.getAsJsonObject("Course").get("CurrentModule").getAsString()), "join -> module PlayerDraft");

        BlockingQueue<JsonObject> pushes = new LinkedBlockingQueue<>();
        premier.joinQueue(json -> pushes.add(JsonParser.parseString(json).getAsJsonObject()), obj("EventName", name));
        JsonObject n;
        int pods = 0;
        String draftId = null;
        while ((n = pushes.poll(10, TimeUnit.SECONDS)) != null) {
            if ("PodNotification".equals(n.get("Type").getAsString())) pods++;
            else if ("ReadyReq".equals(draft(n).get("Type").getAsString())) { draftId = draft(n).get("DraftId").getAsString(); break; }
        }
        check(pods == 8 && draftId != null, "table fills (" + pods + " pod updates), then the ready check");

        Object course = premier.ready(obj("EventName", name, "DraftId", draftId));
        check(course instanceof JsonObject c && draftId.equals(c.get("DraftId").getAsString()), "ready -> course carries the draft id");

        int picks = 0, autos = 0, packInfos = 0, refused = 0;
        boolean completed = false, sawTable = false;
        long start = System.currentTimeMillis();
        while (!completed && (n = pushes.poll(20, TimeUnit.SECONDS)) != null) {
            JsonObject d = draft(n);
            String type = d.get("Type").getAsString();
            if (type.equals("PackInfo")) { packInfos++; continue; }
            if (!type.equals("TableInfo") && !type.equals("PickReq")) continue;
            if (type.equals("TableInfo")) {
                sawTable = true;
                JsonObject t = d.getAsJsonObject("TableInfo");
                check(t.getAsJsonArray("Players").size() == 8 && t.get("SelfSeat").getAsInt() >= 0, "TableInfo: 8 players, seat " + t.get("SelfSeat").getAsInt());
            }
            JsonObject pi = d.getAsJsonObject("PickInfo");
            JsonArray cards = pi.getAsJsonArray("PackCards");
            if (cards.isEmpty()) { autos++; continue; }
            if (picks == 0) {
                JsonObject bad = (JsonObject) premier.pick(obj("DraftId", draftId, "GrpIds", ids(1), "Pack", 1, "Pick", 1));
                if (!bad.get("IsPickSuccessful").getAsBoolean() && bad.get("PickInfo").isJsonObject()) refused++;
                JsonObject packs = (JsonObject) premier.tablePacks(obj("DraftId", draftId));
                check(packs.getAsJsonArray("PacksBySeat").size() == 8, "table packs: one entry per seat");
                check(pi.get("SelfPack").getAsInt() == 1 && pi.get("SelfPick").getAsInt() == 1 && pi.get("TimeoutSec").getAsInt() > 0,
                        "first pick is pack 1 pick 1, " + cards.size() + " cards, " + pi.get("TimeoutSec").getAsInt() + " s");
            }
            JsonObject r = (JsonObject) premier.pick(obj("DraftId", draftId, "GrpIds", ids(cards.get(0).getAsInt()),
                    "Pack", pi.get("SelfPack").getAsInt(), "Pick", pi.get("SelfPick").getAsInt()));
            if (!r.get("IsPickSuccessful").getAsBoolean()) { check(false, "pick " + picks + " refused"); break; }
            picks++;
            completed = r.get("IsPickingCompleted").getAsBoolean();
        }
        check(sawTable, "the draft starts with a TableInfo push");
        check(refused == 1, "a card not in the pack is refused (with the state to resync)");
        check(completed, "picking completes after " + picks + " picks (" + (System.currentTimeMillis() - start) + " ms, " + packInfos + " PackInfo pushes)");

        JsonObject c = (JsonObject) premier.complete(obj("EventName", name, "IsBotDraft", false));
        int pool = c.getAsJsonArray("CardPool").size();
        check("DeckSelect".equals(c.get("CurrentModule").getAsString()) && pool == picks, "pool granted: " + pool + " cards, module DeckSelect");
        JsonElement bots = null;
        for (int i = 0; i < 100 && (bots == null || !bots.isJsonArray()); i++) {      // paced bots may finish after the player
            bots = events.course(name).get("BotDecks");
            if (bots == null) Thread.sleep(100);
        }
        check(bots != null && bots.isJsonArray() && bots.getAsJsonArray().size() >= 5, "bot decks kept for offline matches: " + (bots == null ? 0 : bots.getAsJsonArray().size()));
        check(premier.confirmPool(obj("EventName", name)) instanceof JsonObject, "pool grant confirmed");
        check(events.course(name) != null && events.course(name).get("DraftId").isJsonNull(), "course saved without a draft id");
    }

    private static JsonObject draft(JsonObject push) {
        return push.has("DraftNotification") ? push.getAsJsonObject("DraftNotification") : new JsonObject();
    }

    private static JsonArray ids(int grp) {
        JsonArray a = new JsonArray();
        a.add(grp);
        return a;
    }
}
