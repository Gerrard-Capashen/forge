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

        // The player's profile (identity, name, cosmetics, preferences); -Dmtga.name (launcher "-Name") renames it.
        File dataDir = new File(System.getProperty("mtga.data", new File(clientDir.getCanonicalFile().getParentFile(), "data").getPath()));
        forge.mtga.profile.FileProfileStore profileStore = new forge.mtga.profile.FileProfileStore(dataDir);
        String rename = System.getProperty("mtga.name");
        if (rename != null && !rename.isBlank() && !forge.mtga.profile.Profile.cleanName(rename).equals(profileStore.load().displayName)) {
            profileStore.update(p -> p.displayName = forge.mtga.profile.Profile.cleanName(rename));
        }
        forge.mtga.social.Accounts accounts = null;
        forge.mtga.social.SocialClient social = null;
        // Online play: register the player id with the coordinator (device key, "Name#tag" is unique there) before the client reads the tag.
        if (System.getProperty("mtga.p2p", "online").matches("online|")) {
            accounts = new forge.mtga.social.Accounts(System.getProperty("mtga.coordinator", DEFAULT_COORDINATOR), new forge.mtga.social.IdentityStore(dataDir));
            forge.mtga.social.Accounts.sync(profileStore, accounts);
            if (!"spike".equals(System.getProperty("mtga.social"))) {
                System.setProperty("mtga.social", "online");                 // friends UI on (FrontDoor kill switches), the bridge uses the coordinator's friends
                social = new forge.mtga.social.SocialClient(accounts, profileStore);
                social.start();
            }
        } else if (!"spike".equals(System.getProperty("mtga.social"))) {
            System.setProperty("mtga.social", "off");
        }
        // Mastery (coordinator): the track and the cosmetics granted so far go into profile.json before the client reads it; offline = everything owned
        forge.mtga.social.CoordinatorSession session = accounts == null ? null : new forge.mtga.social.CoordinatorSession(accounts, profileStore);
        forge.mtga.social.MasteryService mastery = new forge.mtga.social.MasteryService(session, profileStore, dataDir);
        mastery.sync();
        if (mastery.online()) System.out.println("[mtga] mastery: level " + mastery.level() + " (" + mastery.xp() + " XP), track " + mastery.track().size() + " levels");
        forge.mtga.profile.Profile me = profileStore.load();
        System.setProperty("mtga.name", me.displayName);       // the Match Door and the engine seats use it
        System.out.println("[mtga] profile: " + me.clientName() + " (" + me.playerId + ")");
        forge.mtga.profile.CosmeticCatalog cosmetics = forge.mtga.profile.CosmeticCatalog.load(clientDir);
        System.out.println("[mtga] cosmetics: " + cosmetics.summary());
        forge.mtga.fd.ProfileApi profileApi = new forge.mtga.fd.ProfileApi(profileStore, cosmetics);

        Map<String, Integer>[] decks = decks(System.getProperty("mtga.deck"), System.getProperty("mtga.rig"));
        Map<String, Integer> green = decks[0], red = decks[1];

        // Each Match Door connection plays the match the Front Door created last (deck chosen in the client), or the
        // hard-coded decks above when there is none (direct-to-duel mode / rig recipes).
        MatchDoorServer server = new MatchDoorServer(port, () -> {
            MatchSetup setup = MatchSetup.current();
            if (setup != null && setup.seat() != null) return setup.seat();
            return setup != null ? new ForgeBackend(db, setup) : new ForgeBackend(db, green, red);
        });
        server.start();

        // Front Door (menus, collection, decks, events) for the client's normal UI; -Dmtga.fdport=0 disables it.
        int fdPort = Integer.getInteger("mtga.fdport", 30010);
        forge.mtga.fd.FrontDoor fd = null;
        if (fdPort > 0) {
            fd = new forge.mtga.fd.FrontDoor(db, dataDir, profileApi);
            fd.setMatchDoor("127.0.0.1", port);
            fd.setPeer(peerConfig(db, clientDir));
            fd.setSocial(social);
            if (accounts != null) fd.setResults(new forge.mtga.social.ResultReporter(accounts, profileStore));
            fd.setMastery(mastery);
            forge.mtga.social.QuestService quests = new forge.mtga.social.QuestService(session);
            quests.refresh();
            fd.setQuests(quests);
            if (accounts != null) fd.setRank(new forge.mtga.social.RankService(session, profileStore, dataDir));
            new forge.mtga.fd.FrontDoorServer(fdPort, fd).start();
        } else {
            System.out.println("[mtga] Front Door disabled (fdport=0)");
        }

        // Scryfall art for the cards CardDbBuilder added; -Dmtga.artport=0 disables it (the client keeps its placeholder).
        int artPort = Integer.getInteger("mtga.artport", 30011);
        if (artPort > 0) {
            forge.mtga.art.ArtServer art = new forge.mtga.art.ArtServer(db, artPort);
            if (social != null) art.addContext("/social/", new forge.mtga.social.SocialEndpoints(social));
            forge.mtga.fd.FrontDoor frontDoor = fd;
            // the bridge asks before the client quits: people at a draft table this server hosts would lose their draft
            if (frontDoor != null) art.addContext("/premier/host", ex -> {
                byte[] body = ("{\"othersDrafting\":" + frontDoor.premierOthersDrafting() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (var out = ex.getResponseBody()) { out.write(body); }
            });
            // the launcher asks before it stops this server: a match result still on its way to the coordinator would be lost
            art.addContext("/results/pending", ex -> {
                byte[] body = ("{\"pending\":" + forge.mtga.social.ResultReporter.pending() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (var out = ex.getResponseBody()) { out.write(body); }
            });
            art.start();
        }
        else System.out.println("[mtga] Art server disabled (artport=0)");
        Thread.currentThread().join();
    }

    /** The matchmaking coordinator everybody uses unless -Dmtga.coordinator says otherwise (a free Cloudflare Worker, see coordinator/). */
    static final String DEFAULT_COORDINATOR = "wss://mm.mtga-forge.workers.dev";

    /**
     * Casual Play pairs players online through the coordinator (-Dmtga.coordinator=wss://... to use another one; -Dmtga.friendcode=<code>
     * only pairs players who typed the same code). -Dmtga.p2p=off: no online play. DEPRECATED: -Dmtga.p2p=host|join, a direct TCP
     * link to one friend (-Dmtga.peerport, -Dmtga.peer=ip[:port], -Dmtga.peerkey); both need the same card DB (MTGA build + this package).
     */
    private static forge.mtga.fd.FrontDoor.Peer peerConfig(CardDb db, File clientDir) throws Exception {
        String role = System.getProperty("mtga.p2p", "online");
        if ("off".equals(role)) {
            System.out.println("[mtga] online play off (-Dmtga.p2p=off): Casual Play is not available");
            return null;
        }
        String fingerprint = forge.mtga.peer.PeerGuest.fingerprint(CardDb.findDatabase(clientDir));
        String key = System.getProperty("mtga.peerkey", "");
        if (role.isBlank() || "online".equals(role)) {
            String url = System.getProperty("mtga.coordinator", DEFAULT_COORDINATOR);
            String code = System.getProperty("mtga.friendcode", "").trim();
            System.out.println("[mtga] online play through " + url + (code.isEmpty() ? "" : " (friend code " + code + ")"));
            return new forge.mtga.fd.FrontDoor.Peer(new forge.mtga.peer.PeerHost(db, 0, key, fingerprint), null, 0, key, fingerprint, url, code);
        }
        if ("host".equals(role)) {
            forge.mtga.peer.PeerHost host = new forge.mtga.peer.PeerHost(db, Integer.getInteger("mtga.peerport", 30012), key, fingerprint);
            host.start();
            return new forge.mtga.fd.FrontDoor.Peer(host, null, 0, key, fingerprint);
        }
        if ("join".equals(role)) {
            String peer = System.getProperty("mtga.peer", "");
            int colon = peer.lastIndexOf(':');
            String h = colon > 0 ? peer.substring(0, colon) : peer;
            if (h.isBlank()) throw new IllegalArgumentException("-Dmtga.p2p=join needs -Dmtga.peer=<host ip>[:port]");
            int p = colon > 0 ? Integer.parseInt(peer.substring(colon + 1)) : 30012;
            System.out.println("[mtga] P2P: playing from the bot queue joins the host at " + h + ":" + p);
            return new forge.mtga.fd.FrontDoor.Peer(null, h, p, key, fingerprint);
        }
        throw new IllegalArgumentException("-Dmtga.p2p must be host, join or online, not " + role);
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
        if ("bf".equals(rig) || "bt".equals(rig) || "vorinclex".equals(rig)) {      // test rig: a passive opponent
            red.clear();
            red.put("Mountain", 40);
        }
        if ("bos".equals(rig)) {      // test rig: Boseiju, Who Endures + Cavern of Souls (choose a creature type) + a Room in hand vs a passive opponent with a nonbasic land
            green.clear();
            green.put("Boseiju, Who Endures", 10);
            green.put("Cavern of Souls", 5);
            green.put("Unholy Annex // Ritual Chamber", 5);
            green.put("Forest", 20);
            red.clear();
            red.put("Command Tower", 10);
            red.put("Mountain", 30);
        }
        if ("scepter".equals(rig)) {      // test rig: Isochron Scepter + Orim's Chant (a spell cast by an effect), Wrath of the Skies (variable energy) vs the bolt deck
            green.clear();
            green.put("Isochron Scepter", 8);
            green.put("Orim's Chant", 8);
            green.put("Wrath of the Skies", 8);
            green.put("Plains", 16);
        }
        if ("sami".equals(rig)) {      // test rig: Sami's granted affinity (ForgeBackend.rigHand sets the board) vs a passive opponent
            green.clear();
            green.put("Plains", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("arrow".equals(rig)) {      // test rig: targeted spells and abilities on the stack must show their target arrow
            green.clear();
            green.put("Mountain", 20);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("room".equals(rig)) {      // test rig: a Room in hand (choose the half, then unlock the other door) vs a passive opponent
            green.clear();
            green.put("Plains", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("venture".equals(rig)) {      // test rig: venture into the dungeon (room choice) vs a passive opponent
            green.clear();
            green.put("Plains", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("addcost".equals(rig)) {      // test rig: optional and alternative additional costs (Bitter Triumph, Iridescent Vinelasher's offspring) vs a passive opponent
            green.clear();
            green.put("Swamp", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("sprawl".equals(rig)) {      // test rig: mana-enhancing Auras (Utopia Sprawl, Wild Growth) vs a passive opponent
            green.clear();
            green.put("Forest", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("gollum".equals(rig)) {      // test rig: Gollum, Riddle Master (choose odd or even as it enters) vs a passive opponent
            green.clear();
            green.put("Swamp", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("ghast".equals(rig)) {      // test rig: modal dies trigger (Shambling Ghast) with a sacrifice outlet vs a passive opponent
            green.clear();
            green.put("Swamp", 40);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("phasing".equals(rig)) {      // test rig: Kaito Shizuki (phases out at the end step of the turn it entered) vs a passive opponent
            green.clear();
            green.put("Island", 20);
            green.put("Swamp", 20);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("mill".equals(rig)) {      // test rig: Traumatize (mill half a library) vs a passive opponent; ForgeBackend.rigHand sets the board
            green.clear();
            green.put("Island", 20);
            green.put("Swamp", 20);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("passage".equals(rig)) {      // test rig: "choose a basic land type" as a land enters (Multiversal Passage) vs a passive opponent
            green.clear();
            green.put("Multiversal Passage", 10);
            green.put("Forest", 30);
            red.clear();
            red.put("Mountain", 40);
        }
        if ("color".equals(rig)) {      // test rig: "choose a color" as a land enters (Room of Refuge) vs a passive opponent
            green.clear();
            green.put("Room of Refuge", 10);
            green.put("Forest", 30);
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
        if ("facedown".equals(deck)) {      // manifest (Whisperwood Elemental) and morph: face-down permanents
            green.clear();
            green.put("Forest", 22);
            green.put("Whisperwood Elemental", 18);
            green.put("Den Protector", 20);
            red.clear();
            red.put("Plains", 40);
        }
        if ("eldrazi".equals(deck)) {      // colorless {C} costs and restricted mana (Eldrazi Temple), vs a passive opponent
            green.clear();
            green.put("Eldrazi Temple", 8);
            green.put("Ugin's Labyrinth", 6);
            green.put("Wastes", 8);
            green.put("Swamp", 4);
            green.put("Kozilek's Command", 10);
            green.put("Thought-Knot Seer", 10);
            green.put("Ulamog's Crusher", 8);
            green.put("Dismember", 6);
            red.clear();
            red.put("Plains", 40);
        }
        if ("sire".equals(deck)) {      // generic {7} paid with restricted {C}{C} sources (rig sire), vs a passive opponent
            green.clear();
            green.put("Eldrazi Temple", 10);
            green.put("Ugin's Labyrinth", 6);
            green.put("Forest", 10);
            green.put("Emrakul, the Aeons Torn", 4);
            green.put("Sire of Seven Deaths", 10);
            green.put("Thought-Knot Seer", 20);
            red.clear();
            red.put("Plains", 40);
        }
        if ("devourer".equals(deck)) {      // opening-hand reveal, upkeep look trigger, cast trigger + creature resolving
            green.clear();
            green.put("Eldrazi Temple", 12);
            green.put("Wastes", 12);
            green.put("Devourer of Destiny", 20);
            green.put("Thought-Knot Seer", 16);
            red.clear();
            red.put("Forest", 16);
            red.put("Grizzly Bears", 24);
        }
        if ("fc".equals(rig)) {      // playtest rig: stops, Full Control, payment, Undo (ForgeBackend.rigHand sets the board) vs an attacking creature deck
            green.clear();
            green.put("Plains", 13);
            green.put("Island", 9);
            green.put("Swords to Plowshares", 4);      // more instants to draw: targets, counters, upkeep plays
            green.put("Counterspell", 4);
            green.put("Silence", 4);
            green.put("Orim's Chant", 4);
            green.put("Wrath of the Skies", 2);
            red.clear();
            red.put("Forest", 16);
            red.put("Grizzly Bears", 24);
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

        if ("menace".equals(rig)) {      // my 2 Grizzly Bears vs the AI's Moonshadow (menace), the AI attacks first
            green.clear();
            green.put("Forest", 16);
            green.put("Grizzly Bears", 24);
            red.clear();
            red.put("Swamp", 16);
            red.put("Moonshadow", 24);
        }

        return new Map[] {green, red};
    }
}
