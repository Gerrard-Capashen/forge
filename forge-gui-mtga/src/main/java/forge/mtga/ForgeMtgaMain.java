package forge.mtga;

import forge.gui.GuiBase;
import forge.model.FModel;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.ForgeBackend;
import forge.mtga.forge.HeadlessGui;
import forge.mtga.net.MatchDoorServer;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local MTGA Match Door backed by Forge. Args: [forgeGuiDir] [clientDir] [port].
 * The patched client copy connects to 127.0.0.1:port (see LocalMatch.cs) and plays against Forge's AI.
 */
public final class ForgeMtgaMain {
    public static void main(String[] args) throws Exception {
        File guiDir = new File(args.length > 0 ? args[0] : ".");
        File clientDir = new File(args.length > 1 ? args[1] : "../../client");
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 30003;

        // pace the client's phase ladder while Forge runs steps nobody stops at (SelfPlay keeps 0)
        if (System.getProperty("mtga.stepdelay") == null) System.setProperty("mtga.stepdelay", "150");
        if (System.getProperty("mtga.capture") != null) forge.mtga.net.Capture.open(System.getProperty("mtga.capture"));
        GuiBase.setInterface(new HeadlessGui(guiDir));
        FModel.initialize(null, null);
        CardDb db = new CardDb(CardDb.findDatabase(clientDir));

        Map<String, Integer>[] decks = decks(System.getProperty("mtga.deck"), System.getProperty("mtga.rig"));
        Map<String, Integer> green = decks[0], red = decks[1];

        // Each Match Door connection plays the match the Front Door created last (deck chosen in the client), or the
        // hard-coded decks above when there is none (direct-to-duel mode / rig recipes).
        MatchDoorServer server = new MatchDoorServer(port, () -> {
            MatchSetup setup = MatchSetup.current();
            return setup != null ? new ForgeBackend(db, setup) : new ForgeBackend(db, green, red);
        });
        server.start();

        // Front Door (menus, collection, decks, events) for the client's normal UI; -Dmtga.fdport=0 disables it.
        int fdPort = Integer.getInteger("mtga.fdport", 30010);
        if (fdPort > 0) {
            File dataDir = new File(System.getProperty("mtga.data", new File(clientDir.getCanonicalFile().getParentFile(), "data").getPath()));
            forge.mtga.fd.FrontDoor fd = new forge.mtga.fd.FrontDoor(db, dataDir);
            fd.setMatchDoor("127.0.0.1", port);
            new forge.mtga.fd.FrontDoorServer(fdPort, fd).start();
        } else {
            System.out.println("[mtga] Front Door disabled (fdport=0)");
        }
        Thread.currentThread().join();
    }

    /** [human deck, AI deck] for a deck name (-Dmtga.deck) / test rig (-Dmtga.rig). */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer>[] decks(String deck, String rig) {
        Map<String, Integer> green = new LinkedHashMap<>();
        green.put("Forest", 16);
        green.put("Llanowar Elves", 4);
        green.put("Grizzly Bears", 20);
        Map<String, Integer> red = new LinkedHashMap<>();
        red.put("Mountain", 16);
        red.put("Lightning Bolt", 24);
        if ("bf".equals(rig)) {      // test rig: a passive opponent
            red.clear();
            red.put("Mountain", 40);
        }

        if ("playtest".equals(deck)) {      // solo sandbox: many card types, opponent only passes
            green.clear();
            String[][] spec = {
                {"Steam Vents", "2"}, {"Breeding Pool", "2"}, {"Watery Grave", "1"}, {"Stomping Ground", "2"}, {"Temple Garden", "1"},
                {"Overgrown Tomb", "1"}, {"Sulfur Falls", "2"}, {"Hinterland Harbor", "2"}, {"Llanowar Wastes", "1"}, {"Command Tower", "2"},
                {"Evolving Wilds", "2"}, {"Island", "2"}, {"Forest", "2"}, {"Mountain", "2"},
                {"Llanowar Elves", "2"}, {"Birds of Paradise", "2"}, {"Elvish Mystic", "1"}, {"Sol Ring", "1"}, {"Mind Stone", "2"}, {"Arcane Signet", "1"},
                {"Ponder", "2"}, {"Brainstorm", "2"}, {"Opt", "2"}, {"Divination", "1"}, {"Counterspell", "2"}, {"Mana Leak", "1"},
                {"Lightning Bolt", "2"}, {"Shock", "1"}, {"Burst Lightning", "2"}, {"Izzet Charm", "1"}, {"Mulldrifter", "2"},
                {"Ravenous Chupacabra", "2"}, {"Pestermite", "1"}, {"Thragtusk", "1"}, {"Elite Spellbinder", "1"}, {"Snapcaster Mage", "1"},
                {"Wall of Omens", "1"}, {"Young Pyromancer", "1"}, {"Fable of the Mirror-Breaker", "1"}, {"Chandra, Torch of Defiance", "1"},
                {"Jace Beleren", "1"}, {"Oko, Thief of Crowns", "1"}, {"Rhystic Study", "1"}, {"Soul Warden", "1"}, {"Bonecrusher Giant", "1"},
                {"Cultivate", "1"}, {"Harrow", "1"}, {"Dismember", "1"}, {"Shivan Dragon", "1"}, {"Serra Angel", "1"},
                {"Jadelight Ranger", "2"}, {"Merfolk Branchwalker", "1"}, {"Requiting Hex", "2"},
                {"Ninja of the Deep Hours", "2"}, {"Spirebluff Canal", "2"}, {"Stormchaser's Talent", "1"},
            };
            int total = 0;
            for (String[] e : spec) {
                green.put(e[0], Integer.parseInt(e[1]));
                total += Integer.parseInt(e[1]);
            }
            green.merge("Forest", Math.max(0, 60 - total), Integer::sum);
            red.clear();
            red.put("Plains", 40);
        }
        if ("newcards".equals(deck)) {      // cards added to the client DB by CardDbBuilder, vs a passive opponent
            green.clear();
            green.put("Swamp", 12);
            green.put("Forest", 12);
            green.put("Grizzly Bears", 20);
            green.put("Blade of the Bloodchief", 16);
            red.clear();
            red.put("Plains", 40);
        }
        if ("newfaces".equals(deck)) {      // multi-face cards added by CardDbBuilder: transform, split (fuse), flip
            green.clear();
            green.put("Island", 16);
            green.put("Forest", 6);
            green.put("Mountain", 8);
            green.put("Aberrant Researcher", 8);
            green.put("Beck // Call", 6);
            green.put("Akki Lavarunner", 8);
            green.put("Lightning Bolt", 8);
            red.clear();
            red.put("Plains", 40);
        }
        if ("bolt".equals(rig)) {      // test rig: burn deck vs creature deck
            green.clear();
            green.put("Mountain", 16);
            green.put("Lightning Bolt", 24);
            red.clear();
            red.put("Forest", 16);
            red.put("Grizzly Bears", 24);
        }

        if ("block".equals(rig)) {
            green.clear();
            green.put("Forest", 16);
            green.put("Llanowar Elves", 24);
            red.clear();
            red.put("Mountain", 16);
            red.put("Grizzly Bears", 24);
        }

        return new Map[] {green, red};
    }
}
