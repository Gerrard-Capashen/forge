package forge.mtga.fd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.wizards.mtga.fd.*;
import forge.StaticData;
import forge.mtga.forge.CardDb;
import forge.mtga.net.Capture;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Front Door command handlers. Each request carries a CmdType and a JSON or protobuf payload; the reply uses the same
 * encoding the client chose for that call (it deserializes JSON calls with Newtonsoft and protobuf calls with
 * Any.Unpack). Unknown commands are logged as "FD UNHANDLED" and answered with a ServerError.
 */
public final class FrontDoor {
    /** Handler result: a JSON string, a protobuf Message, or an {@link Err}. */
    interface Handler { Object handle(FdSession s, Req r) throws Exception; }

    record Req(String type, String transId, JsonElement json, Any any) {
        JsonObject obj() { return json != null && json.isJsonObject() ? json.getAsJsonObject() : new JsonObject(); }
    }

    record Err(int code, String message) { }

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final int ERR_NOT_IMPLEMENTED = 1;

    private final CardDb db;
    private final DeckStore decks;
    private final DraftEvents events;
    private final File prefsFile;
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, String> prefs = new TreeMap<>();
    private String collectionJson;
    private GetFormatsResponse formats;
    private volatile FdSession current;

    public FrontDoor(CardDb db, File dataDir) {
        this.db = db;
        this.decks = new DeckStore(new File(dataDir, "decks"));
        this.events = new DraftEvents(db, dataDir);
        this.prefsFile = new File(dataDir, "preferences.json");
        loadPrefs();
        seedStarterDecks(dataDir);
        register();
        System.out.println("[mtga] FD data dir " + dataDir.getAbsolutePath() + " (" + decks.all().size() + " decks)");
    }

    public DeckStore decks() { return decks; }

    /** The connected client, if any (push notifications). */
    public FdSession current() { return current; }

    void onConnect(FdSession s) { current = s; }

    void onDisconnect(FdSession s) { if (current == s) current = null; }

    // ---- dispatch -----------------------------------------------------------------------------------------------

    void handle(FdSession s, Cmd cmd) {
        // proto enum names keep underscores (Graph_GetGraphDefinitions), the C# CmdType names do not: key by the C# name
        String type = cmd.getType() == CmdType.UNRECOGNIZED ? "CmdType#" + cmd.getTypeValue()
                : cmd.getType().name().replaceFirst("^CmdType_", "").replace("_", "");
        String transId = cmd.getRawTransId();
        JsonElement json = null;
        Any any = null;
        if (cmd.hasProtobufPayload()) {
            any = cmd.getProtobufPayload();
            Capture.log("FD <- " + type + " proto " + any.getTypeUrl());
        } else {
            String text = cmd.getJsonPayload().toString(StandardCharsets.UTF_8);
            Capture.log("FD <- " + type + " " + Capture.flat(text, 600));
            try { json = text.isBlank() ? null : JsonParser.parseString(text); } catch (Exception e) { json = null; }
        }
        Handler h = handlers.get(type);
        if (h == null) {
            System.out.println("[mtga] FD UNHANDLED " + type);
            Capture.log("FD UNHANDLED " + type + (any != null ? " " + any.getTypeUrl() : " " + Capture.flat(json, 300)));
            s.replyError(transId, ERR_NOT_IMPLEMENTED, "ForgeBridge: " + type + " not implemented");
            return;
        }
        try {
            Object out = h.handle(s, new Req(type, transId, json, any));
            if (out instanceof Err e) {
                Capture.log("FD -> " + type + " ERROR " + e.code() + " " + e.message());
                s.replyError(transId, e.code(), e.message());
            } else if (out instanceof Message m) {
                Capture.log("FD -> " + type + " proto " + m.getDescriptorForType().getName() + " " + m.getSerializedSize() + " bytes");
                s.replyProto(transId, m);
            } else {
                String text = out == null ? "{}" : out instanceof String str ? str : GSON.toJson(out);
                Capture.log("FD -> " + type + " " + Capture.flat(text, 400));
                s.replyJson(transId, text);
            }
        } catch (Exception e) {
            System.out.println("[mtga] FD handler " + type + " failed: " + e);
            e.printStackTrace(System.out);
            Capture.log("FD -> " + type + " EXCEPTION " + e);
            s.replyError(transId, 2, "ForgeBridge: " + e);
        }
    }

    private void on(String type, Handler h) { handlers.put(type, h); }

    // ---- handlers -----------------------------------------------------------------------------------------------

    private void register() {
        Handler session = (s, r) -> obj("SessionId", "forge-local-session", "Attached", true, "PlayerConditions", 0);
        on("Authenticate", session);
        on("Attach", session);

        on("StartHook", (s, r) -> startHook());
        on("CardGetAllCards", (s, r) -> collection());
        on("GetFormats", (s, r) -> formats());
        on("GetDesignerMetadata", (s, r) -> obj("NonCraftableCardList", new JsonArray(), "NonCollectibleCardList", new JsonArray(),
                "UnreleasedSets", new JsonArray()));
        on("GetKillSwitchData", (s, r) -> {
            JsonObject ks = new JsonObject();
            for (String k : KILL_SWITCHES) ks.addProperty(k, true);
            return obj("KillSwitches", ks, "UxKillSwitches", ks.deepCopy());
        });
        on("EventGetActiveMatches", (s, r) -> obj("MatchesV3", new JsonArray()));

        // player preferences: a string map persisted next to the decks
        on("GetPlayerPreferences", (s, r) -> prefsJson());
        on("SetPlayerPreferences", (s, r) -> {
            JsonObject p = r.obj().has("Preferences") && r.obj().get("Preferences").isJsonObject()
                    ? r.obj().getAsJsonObject("Preferences") : new JsonObject();
            JsonElement map = p.get("Preferences");
            if (map != null && map.isJsonObject()) {
                synchronized (prefs) {
                    for (Map.Entry<String, JsonElement> e : map.getAsJsonObject().entrySet()) {
                        prefs.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                    }
                    savePrefs();
                }
            }
            return prefsJson();
        });

        // decks
        on("DeckGetDeckSummariesV3", (s, r) -> {
            JsonArray list = new JsonArray();
            for (JsonObject d : decks.all()) list.add(d.get("Summary"));
            return obj("Summaries", list);
        });
        on("DeckGetDeck", (s, r) -> {
            String id = str(r.obj(), "DeckId");
            JsonObject d = id == null ? null : decks.get(id);
            return d == null ? new Err(404, "no deck " + id) : d.get("Deck").toString();
        });
        on("DeckUpsertDeckV3", (s, r) -> {
            JsonObject summary = r.obj().getAsJsonObject("Summary");
            JsonObject deck = r.obj().getAsJsonObject("Deck");
            String id = str(summary, "DeckId");
            if (id == null || deck == null) return new Err(400, "bad upsert");
            decks.put(id, summary, deck);
            System.out.println("[mtga] FD saved deck " + str(summary, "Name") + " (" + id + ")");
            return summary.toString();
        });
        on("DeckDeleteDeck", (s, r) -> {
            String id = str(r.obj(), "DeckId");
            boolean ok = id != null && decks.delete(id);
            System.out.println("[mtga] FD deleted deck " + id + ": " + ok);
            return "Success";
        });

        // campaign graphs: the new-player graphs, all completed/unlocked (no tutorial, no locked play modes offline)
        on("GraphGetGraphDefinitions", (s, r) -> {
            JsonArray defs = new JsonArray();
            String requested = str(r.obj(), "RequestedType");
            for (Graph g : GRAPHS) {
                if (requested == null || requested.equals("None") || requested.equals("-1") || requested.equals(g.type())) defs.add(g.definition());
            }
            return obj("GraphDefinitions", defs);
        });
        on("GraphGetGraphState", (s, r) -> graphState(str(r.obj(), "GraphId")));
        on("GraphProcessV2", (s, r) -> {
            String graphId = str(r.obj(), "GraphId");
            JsonObject states = new JsonObject();
            states.add(graphId == null ? "" : graphId, graphState(graphId));
            return obj("GraphStates", states, "InventoryInfo", null);
        });

        // ---- play: one bot-match queue ("AIBotMatch") whose matches run on the local Match Door vs the Forge AI ----
        on("GetPlayBladeQueueConfig", (s, r) -> {
            JsonArray q = new JsonArray();
            q.add(obj("Id", "forge-ai", "QueueType", "Unranked", "LocTitle", "Events/Event_Title_AIBotMatch",
                    "EventNameBO1", BOT_EVENT, "EventNameBO3", null, "DeckSizeBO1", null, "DeckSizeBO3", null,
                    "SideBoardBO1", null, "SideBoardBO3", null, "Tags", new JsonArray()));
            return q.toString();
        });
        on("EventGetActiveEventsV2", (s, r) -> {
            JsonArray bots = new JsonArray();
            bots.add(botEvent(BOT_EVENT, ForgeFormats.ANY, 100));
            bots.add(botEvent(BOT_EVENT + "_Rebalanced", "Alchemy", 99));
            return obj("DynamicFilterTags", new JsonArray(), "CacheVersion", 1, "Events", events.eventInfos(),
                    "Challenges", null, "AiBotMatches", bots);
        });
        on("EventGetCoursesV2", (s, r) -> obj("Courses", events.courseList()));

        // ---- quick drafts: one event per Forge set, bot pod run by Forge (see DraftEvents) ----
        on("EventJoin", (s, r) -> events.join(r.obj()));
        on("BotDraftStartDraft", (s, r) -> events.startDraft(r.obj()));
        on("BotDraftDraftStatus", (s, r) -> events.draftStatus(r.obj()));
        on("BotDraftDraftPick", (s, r) -> events.draftPick(r.obj()));
        on("EventSetDeckV3", (s, r) -> events.setDeck(r.obj()));
        on("EventDrop", (s, r) -> events.drop(r.obj(), true));
        on("EventResign", (s, r) -> events.drop(r.obj(), false));
        on("EventEnterPairing", (s, r) -> startEventMatch(s, str(r.obj(), "EventName")));
        on("EventAiBotMatch", (s, r) -> startBotMatch(s, r.obj()));
        on("EventLeavePairing", (s, r) -> {      // the client left the "waiting for a match" screen
            Peer p = peer;
            if (p != null && p.host() != null) p.host().cancel();
            return GSON.toJson("Success");
        });
        // after a match: no rewards/rank/quests offline, just "found" so the end screen continues to the home page
        on("EventGetMatchResultReport", (s, r) -> obj("CurrentModule", lastEvent != null ? events.module(lastEvent) : "Complete", "FoundMatch", true, "InventoryInfo", null,
                "questUpdates", new JsonArray(), "rankUpdates", null, "mythicRatingUpdates", null, "periodicRewardsProgress", null));

        // ---- neutral answers so the home page has no errors (no store, quests, rank, rewards, social offline) ----
        on("StaticContent", (s, r) -> obj());
        on("RankGetCombinedRankInfo", (s, r) -> obj("constructedSeasonOrdinal", 1, "constructedClass", "Bronze", "constructedLevel", 4,
                "constructedStep", 0, "constructedMatchesWon", 0, "constructedMatchesLost", 0, "constructedMatchesDrawn", 0,
                "limitedSeasonOrdinal", 1, "limitedClass", "Bronze", "limitedLevel", 4, "limitedStep", 0, "limitedMatchesWon", 0,
                "limitedMatchesLost", 0, "limitedMatchesDrawn", 0, "constructedPercentile", 0, "constructedLeaderboardPlace", 0,
                "limitedPercentile", 0, "limitedLeaderboardPlace", 0));
        on("RankGetSeasonAndRankDetails", (s, r) -> obj("currentSeason", obj("seasonOrdinal", 1, "seasonStartTime", "2020-01-01T00:00:00Z",
                "seasonEndTime", "2099-01-01T00:00:00Z", "seasonLimitedRewards", obj(), "seasonConstructedRewards", obj(), "minMatches", 0),
                "limitedRankInfo", new JsonArray(), "constructedRankInfo", new JsonArray()));
        on("RenewalGetCurrentRenewal", (s, r) -> obj("currentId", null, "definitionId", null, "initialPayoutsStartTime", "2099-01-01T00:00:00Z",
                "initialPayoutsEndTime", "2099-01-01T00:00:00Z", "rotatingSets", new JsonArray(), "ready", false));
        on("DeckGetAllPreconDecksV3", (s, r) -> obj("CacheVersion", 1, "PreconDecks", new JsonArray()));
        on("GetNetDeckFolders", (s, r) -> "[]");
        on("GetAllPrizeWalls", (s, r) -> obj("ActivePrizeWalls", new JsonArray()));
        on("GetAllPreferredPrintings", (s, r) -> "{}");
        on("MercGetStoreStatusV2", (s, r) -> obj("DisabledTags", new JsonArray(), "DisabledListings", new JsonArray(),
                "CodeRedemptionEnabled", false, "StoreEnabled", false));
        on("QuestGetQuests", (s, r) -> obj("canSwap", false, "newQuests", 0, "quests", new JsonArray()));
        on("PeriodicRewardsGetStatus", (s, r) -> obj("_dailyRewardSequenceId", 0, "_dailyRewardResetTimestamp", "2099-01-01T00:00:00Z",
                "_weeklyRewardSequenceId", 0, "_weeklyRewardResetTimestamp", "2099-01-01T00:00:00Z",
                "_dailyRewardChestDescriptions", obj(), "_weeklyRewardChestDescriptions", obj()));
        on("CarouselGetCarouselItems", (s, r) -> "[]");
        // no store offline: failing the listings keeps the Store tab locked (an empty catalogue opens a Store page that
        // hangs on "Waiting for the Server" because its set filter has no entries)
        on("MercGetSkusAndListings", (s, r) -> new Err(ERR_NOT_IMPLEMENTED, "no store offline"));
        // no season payouts offline: a plain error makes the client skip the payout popup
        on("RankEvaluatePayoutsV2", (s, r) -> new Err(ERR_NOT_IMPLEMENTED, "no season payouts offline"));
        on("StoreGetEntitlementsV2", (s, r) -> obj("InventoryInfo", null, "ContainsFirstPurchase", false));
        on("GetVoucherDefinitions", (s, r) -> GetVoucherDefinitionsResponse.getDefaultInstance());
        on("GetPlayerInbox", (s, r) -> GetPlayerInboxResp.getDefaultInstance());
        on("GetWpnNotificationPreferences", (s, r) -> {
            WpnNotificationPreference off = WpnNotificationPreference.getDefaultInstance();
            return GetWpnNotificationPreferencesResp.newBuilder().setDefaultWpnNotificationPreference(off).setGlobalNotificationPreference(off).build();
        });
        on("ChallengeReconnectAll", (s, r) -> ChallengeReconnectAllResp.getDefaultInstance());
        Handler cosmetics = (s, r) -> PreferredCosmetics.getDefaultInstance();
        for (String c : new String[] {"CosmeticsGetPreferredCosmetics", "CosmeticsSetPreferredAvatar", "CosmeticsSetPreferredSleeve",
                "CosmeticsSetPreferredPet", "CosmeticsSetPreferredTitle", "CosmeticsSetPreferredEmotes"}) on(c, cosmetics);
        on("GetSets", (s, r) -> sets());

        // telemetry: swallow
        Handler ok = (s, r) -> "";
        on("LogBusinessEvents", ok);
        on("LogBusinessEventsV2", ok);
    }

    // ---- bot match --------------------------------------------------------------------------------------------

    static final String BOT_EVENT = "AIBotMatch";
    private static final java.util.concurrent.ScheduledExecutorService LATER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fd-push");
        t.setDaemon(true);
        return t;
    });
    private int matchCounter;
    /** Draft event of the last match started, for the end-of-match report. */
    private volatile String lastEvent;
    /** Match Door endpoint announced in MatchCreated (the Java Match Door, same process). */
    private String matchHost = "127.0.0.1";
    private int matchPort = 30003;

    public void setMatchDoor(String host, int port) { matchHost = host; matchPort = port; }

    /** Player-vs-player settings: {@code host} set = this server hosts (waits for one guest), else it joins {@code joinHost:joinPort}. */
    public record Peer(forge.mtga.peer.PeerHost host, String joinHost, int joinPort, String key, String fingerprint) { }

    private volatile Peer peer;

    public void setPeer(Peer p) { peer = p; }

    private static JsonObject botEvent(String internalName, String format, int priority) {
        return obj("PublicEventName", BOT_EVENT, "InternalEventName", internalName, "Format", format,
                "WinCondition", "SingleElimination", "StartingPlayer", "Random", "TimerPackage", "None",
                "DeckIds", new JsonArray(), "DisplayPriority", priority);
    }

    /** EventAiBotMatch{deckId, botDeckId, botMatchType}: set up the Forge match, reply, then push MatchCreated. */
    private Object startBotMatch(FdSession s, JsonObject req) {
        String deckId = str(req, "deckId");
        JsonObject stored = deckId == null ? null : decks.get(deckId);
        if (stored == null) return new Err(7000, "deck " + deckId + " not found");
        return startMatch(s, stored.getAsJsonObject("Summary"), stored.getAsJsonObject("Deck"), null, BOT_EVENT);
    }

    /** EventEnterPairing for a draft event: play the course's deck against one of the bots' drafted decks. */
    private Object startEventMatch(FdSession s, String eventName) {
        JsonObject course = DraftEvents.isDraftEvent(eventName) ? events.course(eventName) : null;
        if (course == null || !course.has("CourseDeck") || !course.get("CourseDeck").isJsonObject()) return new Err(7000, "no deck for event " + eventName);
        Map<String, Integer> bot = events.botDeck(eventName);
        forge.mtga.AiDecks.Choice ai = bot == null ? null : new forge.mtga.AiDecks.Choice("draft bot", bot);
        return startMatch(s, course.getAsJsonObject("CourseDeckSummary"), course.getAsJsonObject("CourseDeck"), ai, eventName);
    }

    /** Match for a deck in client JSON: {@code aiOverride} null = AI deck chosen from the deck's format. */
    private Object startMatch(FdSession s, JsonObject summary, JsonObject deck, forge.mtga.AiDecks.Choice aiOverride, String eventId) {
        String deckName = str(summary, "Name");

        Map<String, Integer> human = new LinkedHashMap<>();
        Map<String, Integer> printing = new HashMap<>();
        java.util.List<String> unknown = new java.util.ArrayList<>();
        JsonArray main = deck.getAsJsonArray("MainDeck");
        if (main != null) {
            for (JsonElement e : main) {
                JsonObject c = e.getAsJsonObject();
                int grp = c.get("cardId").getAsInt(), qty = c.get("quantity").getAsInt();
                String name = db.name(grp);
                if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) {
                    unknown.add(grp + (name == null ? "" : " " + name));
                    continue;
                }
                human.merge(name, qty, Integer::sum);
                printing.putIfAbsent(name, grp);
            }
        }
        if (human.isEmpty()) return new Err(7000, "deck '" + deckName + "' has no cards Forge knows");
        if (!unknown.isEmpty()) System.out.println("[mtga] FD: skipped cards Forge does not know: " + unknown);

        if (peer != null) return startPeerMatch(s, deckName, human, printing);
        forge.mtga.AiDecks.Choice ai = aiOverride != null ? aiOverride : aiDeck(deckFormat(summary));
        String matchId = "forge-" + System.currentTimeMillis() + "-" + (++matchCounter);
        String aiName = "Forge AI";
        boolean event = DraftEvents.isDraftEvent(eventId);
        lastEvent = event ? eventId : null;
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, ai.cards(), printing, aiName, null,
                event ? won -> events.recordResult(eventId, won) : null));
        int total = human.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("[mtga] FD bot match " + matchId + " (" + eventId + "): '" + deckName + "' (" + total + " cards) vs AI deck '" + ai.name() + "'");
        Capture.log("FD bot match " + matchId + " deck '" + deckName + "' vs '" + ai.name() + "'");

        pushMatchCreated(s, matchId, aiName + " (" + ai.name() + ")", eventId);
        return GSON.toJson(matchId);
    }

    /**
     * Player vs player instead of a bot match (-Dmtga.p2p). Host: this client's seat is built now and the client waits in its
     * queue screen until a guest joins. Guest: connects to the host, then plays through the host's engine.
     * Either way the client's own Match Door connection (127.0.0.1) is what it plays on.
     */
    private Object startPeerMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing) {
        String myName = System.getProperty("mtga.name", "Forge Player");
        Peer p = peer;
        if (p.host() != null) {
            String matchId = "p2p-" + System.currentTimeMillis() + "-" + (++matchCounter);
            forge.mtga.forge.ForgeMatch room = new forge.mtga.forge.ForgeMatch(db, matchId);
            forge.mtga.forge.ForgeBackend mine = new forge.mtga.forge.ForgeBackend(db, room, 0, new forge.mtga.forge.ForgeMatch.Seat(myName, human, printing));
            forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, Map.of(), printing, "", mine));
            System.out.println("[mtga] FD P2P host: '" + deckName + "' waits for a guest (match " + matchId + ")");
            p.host().offer(new forge.mtga.peer.PeerHost.Offer(matchId, room, guest -> pushMatchCreated(s, matchId, guest)));
            return GSON.toJson(matchId);
        }
        forge.mtga.peer.PeerGuest.Joined j = forge.mtga.peer.PeerGuest.join(p.joinHost(), p.joinPort(), p.key(), p.fingerprint(), myName, human, printing);
        if (j.error() != null) return new Err(7000, "P2P: " + j.error());
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(j.matchId(), human, Map.of(), printing, "", j.backend()));
        System.out.println("[mtga] FD P2P guest: '" + deckName + "' vs " + j.hostName() + " (match " + j.matchId() + ")");
        pushMatchCreated(s, j.matchId(), j.hostName());
        return GSON.toJson(j.matchId());
    }

    /** MatchCreated for this client: it is seat 1 on the Match Door of this process, the opponent seat 2. */
    private void pushMatchCreated(FdSession s, String matchId, String opponentLabel) { pushMatchCreated(s, matchId, opponentLabel, BOT_EVENT); }

    private void pushMatchCreated(FdSession s, String matchId, String opponentLabel, String eventId) {
        JsonArray players = new JsonArray();
        players.add(obj("ScreenName", System.getProperty("mtga.name", "Forge Player"), "RankingClass", "Bronze", "RankingTier", 4, "SeatId", 1, "TeamId", 1, "IsWotc", false));
        players.add(obj("ScreenName", opponentLabel, "RankingClass", "Bronze", "RankingTier", 4, "SeatId", 2, "TeamId", 2, "IsWotc", false));
        JsonObject info = obj("MatchEndpointHost", matchHost, "MatchEndpointPort", matchPort, "MatchId", matchId,
                "McFabricId", "fabric:/forge/" + matchId, "EventId", eventId, "Battlefield", "", "MatchType", "Queue",
                "YourSeat", 1, "ClientMetadata", obj(), "PlayerInfos", players);
        String push = obj("Type", "MatchCreated", "MatchInfoV4", info).toString();
        LATER.schedule(() -> {
            Capture.log("FD push MatchCreated " + Capture.flat(push, 400));
            s.push(push);
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The deck's "Format" attribute (the deck builder's format dropdown), null if none. */
    static String deckFormat(JsonObject summary) {
        JsonElement attrs = summary == null ? null : summary.get("Attributes");
        if (attrs == null || !attrs.isJsonArray()) return null;
        for (JsonElement a : attrs.getAsJsonArray()) {
            JsonObject o = a.getAsJsonObject();
            if ("Format".equals(str(o, "name"))) return str(o, "value");
        }
        return null;
    }

    /**
     * AI deck: -Dmtga.aideck if set; else, for a deck in a Forge-defined 60-card format (Modern, Pioneer...), a deck
     * Forge generates for that format from cards the client has; else a random Forge "MTGA ..." precon.
     */
    private forge.mtga.AiDecks.Choice aiDeck(String format) {
        String spec = System.getProperty("mtga.aideck");
        if (spec == null || spec.isBlank()) {
            forge.game.GameFormat gf = ForgeFormats.forgeFormat(format);
            if (gf != null && !"Commander".equals(gf.getName())) {
                System.out.println("[mtga] FD: AI deck for format " + format);
                return forge.mtga.AiDecks.forFormat(gf, n -> db.grpId(n) != 0);
            }
        }
        return forge.mtga.AiDecks.pick(spec);
    }

    // ---- set metadata -------------------------------------------------------------------------------------------

    private SetMetadataResponse setsResponse;

    private synchronized SetMetadataResponse sets() {
        if (setsResponse != null) return setsResponse;
        // Release dates: Forge's edition date when Forge knows the set code; an Arena-only set (Y26, ...) gets the day
        // after the set released before it, in GrpId order (newer sets have higher GrpIds; cards added by
        // CardDbBuilder have reserved GrpIds and do not count). The client needs a group "AllFilters" (deck builder set
        // filter) and at least one published major set ("last published major set").
        Map<String, Integer> newest = new HashMap<>();
        for (CardDb.Printing p : db.printings()) {
            if (p.expansion() != null && !p.expansion().isBlank()) {
                newest.merge(p.expansion(), p.grpId() >= forge.mtga.CardDbBuilder.GRP_BASE ? 0 : p.grpId(), Math::max);
            }
        }
        java.util.List<String> byGrp = new java.util.ArrayList<>(newest.keySet());
        byGrp.sort(java.util.Comparator.comparing(newest::get));
        long day = 86_400L, base = 946_684_800L;   // 2000-01-01
        Map<String, Long> released = new HashMap<>();
        long previous = base;
        for (String code : byGrp) {
            forge.card.CardEdition ed = StaticData.instance().getCardEdition(code);
            long t = ed != null && ed.getDate() != null ? ed.getDate().getTime() / 1000 : previous + day;
            released.put(code, t);
            previous = t;
        }
        java.util.List<String> codes = new java.util.ArrayList<>(byGrp);
        codes.sort(java.util.Comparator.comparing(released::get));
        SetMetadataResponse.Builder b = SetMetadataResponse.newBuilder();
        // per set: its collectible printings (profile "set collection" badge/page) and one collation named after the set
        // (the client only keeps collations whose code is a known CollationMapping; they give the release ordering)
        Map<String, java.util.List<Integer>> grpsBySet = new HashMap<>();
        for (CardDb.Printing p : db.printings()) {
            boolean alchemy = p.digitalSet() != null && !p.digitalSet().isBlank();
            if (p.expansion() != null && !p.expansion().isBlank() && !alchemy) grpsBySet.computeIfAbsent(p.expansion(), k -> new java.util.ArrayList<>()).add(p.grpId());
        }
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            b.addSetDatas(SetMetadata.newBuilder().setSetCode(code).setAvailabilityInternal(2)   // SetAvailability.Available
                    .setIsPublished(true).setIsMajorCardset(true)
                    .addCollations(SetCollation.newBuilder().setCollationCode(code))
                    .setReleaseDate(Timestamp.newBuilder().setSeconds(released.get(code))));
            java.util.List<Integer> grps = grpsBySet.get(code);
            if (grps != null) b.addSetCollectionGroup(KeyValuePair_String_List_Int32.newBuilder().setKey(code).addAllValue(grps));
        }
        // The home page announces the newest published major set ("Available Now! Visit the store...") when its code is
        // a real collation. An empty sentinel set dated after every real one is the "last published major set" instead.
        b.addSetDatas(SetMetadata.newBuilder().setSetCode("FORGE").setAvailabilityInternal(2).setIsPublished(true).setIsMajorCardset(true)
                .setReleaseDate(Timestamp.newBuilder().setSeconds(released.get(codes.get(codes.size() - 1)) + day)));
        java.util.List<String> newestFirst = new java.util.ArrayList<>(codes);
        java.util.Collections.reverse(newestFirst);
        b.addSetGroups(SetGroup.newBuilder().setGroupName("AllFilters").addAllSets(newestFirst));
        setsResponse = b.build();
        System.out.println("[mtga] FD set metadata: " + codes.size() + " sets, newest " + newestFirst.get(0));
        return setsResponse;
    }

    // ---- campaign graphs ----------------------------------------------------------------------------------------

    /** A campaign graph whose nodes are all Completed and whose milestones are all reached. */
    record Graph(String id, String type, String[][] nodes, String[] completedGroup, String[] milestones) {
        JsonObject definition() {
            JsonObject ns = new JsonObject();
            for (String[] n : nodes) ns.add(n[0], obj("Id", n[0], "Type", n[1], "Children", new JsonArray()));
            JsonArray groups = new JsonArray();
            if (completedGroup.length > 0) {
                JsonArray members = new JsonArray();
                for (String n : completedGroup) members.add(n);
                groups.add(obj("Name", "Completed_Group", "Nodes", members));
            }
            return obj("Id", id, "Type", type, "StartTime", "2020-01-01T00:00:00Z", "EndRevealTime", "2099-01-01T00:00:00Z",
                    "EndTime", "2099-01-01T00:00:00Z", "NodeGroups", groups, "Nodes", ns, "Milestones", new JsonArray());
        }

        JsonObject state() {
            JsonObject ns = new JsonObject();
            for (String[] n : nodes) ns.add(n[0], obj("Status", "Completed"));
            JsonObject ms = new JsonObject();
            for (String m : milestones) ms.addProperty(m, true);
            return obj("NodeStates", ns, "MilestoneStates", ms);
        }
    }

    // NodeNpeStrategy needs "NPE_Tutorial" with a completed Completed_Group node; SparkyTourState unlocks every play mode
    // when ColorChallenge's "UnlockPlayMode" node is completed; NewPlayerExperience milestones gate events/ranked/store.
    static final Graph[] GRAPHS = {
        new Graph("NPE_Tutorial", "NPETutorial", new String[][] {{"NPE_Done", "ManualComplete"}}, new String[] {"NPE_Done"}, new String[0]),
        new Graph("ColorChallenge", "ColorChallenge", new String[][] {{"UnlockPlayMode", "ManualComplete"}}, new String[0],
                new String[] {"ColorChallengeComplete"}),
        new Graph("NewPlayerExperience", "NPE", new String[][] {{"UnlockPlayModes", "ManualComplete"}}, new String[0],
                new String[] {"OpenDualColorPreconEvent", "OpenSparkQueue", "GraduateSparkRank", "NPE_Completed"}),
    };

    private static JsonObject graphState(String graphId) {
        for (Graph g : GRAPHS) if (g.id().equals(graphId)) return g.state();
        return obj("NodeStates", new JsonObject(), "MilestoneStates", new JsonObject());
    }

    // ---- starter decks ------------------------------------------------------------------------------------------

    /**
     * First start only (marker file data/.seeded): copy Forge's "MTGA ..." precons into the player's decks so there are
     * ready 60-card decks to play and edit. Delete the marker to seed again.
     */
    private void seedStarterDecks(File dataDir) {
        File marker = new File(dataDir, ".seeded");
        if (marker.exists()) return;
        File[] precons = new File(forge.localinstance.properties.ForgeConstants.QUEST_PRECON_DIR)
                .listFiles((d, n) -> n.startsWith("MTGA ") && n.endsWith(".dck"));
        int made = 0;
        if (precons != null) {
            java.util.Arrays.sort(precons);
            for (File f : precons) {
                try {
                    forge.mtga.AiDecks.Choice c = forge.mtga.AiDecks.pick(f.getPath());
                    if (saveDeck("Forge: " + c.name(), c.cards())) made++;
                } catch (Exception e) {
                    System.out.println("[mtga] FD: cannot seed " + f.getName() + ": " + e);
                }
            }
        }
        try {
            marker.getParentFile().mkdirs();
            Files.writeString(marker.toPath(), "starter decks seeded: " + made + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot write " + marker + ": " + e);
        }
        System.out.println("[mtga] FD seeded " + made + " starter decks from Forge precons");
    }

    /** Store a deck given by Forge card names, in the same JSON shape the client writes (DeckUpsertDeckV3). */
    private boolean saveDeck(String name, Map<String, Integer> cards) throws java.io.IOException {
        JsonArray main = new JsonArray();
        int tile = 0, total = 0;
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            int grp = db.grpId(e.getKey());
            if (grp == 0) {
                System.out.println("[mtga] FD: '" + name + "': client has no card " + e.getKey());
                continue;
            }
            main.add(obj("cardId", grp, "quantity", e.getValue()));
            total += e.getValue();
            boolean basic = e.getKey().matches("Plains|Island|Swamp|Mountain|Forest|Wastes");
            if (tile == 0 && !basic) tile = grp;
        }
        if (total < 40) return false;
        String id = java.util.UUID.randomUUID().toString();
        String now = java.time.OffsetDateTime.now().toString();
        JsonArray attrs = new JsonArray();
        attrs.add(obj("name", "Version", "value", "11"));
        attrs.add(obj("name", "TileID", "value", Integer.toString(tile)));
        attrs.add(obj("name", "LastPlayed", "value", "\"0001-01-01T00:00:00\""));
        attrs.add(obj("name", "LastUpdated", "value", "\"" + now + "\""));
        attrs.add(obj("name", "IsFavorite", "value", "false"));
        attrs.add(obj("name", "Format", "value", "Timeless"));
        JsonObject summary = obj("DeckId", id, "Mana", "", "Name", name, "Attributes", attrs, "Description", null,
                "DeckTileId", tile, "DeckArtId", db.artId(tile), "IsCompanionValid", false,
                "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
        JsonObject deck = obj("MainDeck", main, "Sideboard", new JsonArray(), "CommandZone", new JsonArray(),
                "Companions", new JsonArray(), "CardSkins", new JsonArray());
        decks.put(id, summary, deck);
        return true;
    }

    /**
     * Client features switched off offline (kill switch true = feature disabled): they talk to Wizards web services
     * (WPN store locator, player reporting, Hasbro Go social/friends) or need server data we do not model (achievements).
     */
    static final String[] KILL_SWITCHES = {"WPNFavoriteStoreStatus", "PlayerReportingStatus", "HasbroGoAddRemoveFriends",
            "SocialBladeStatus", "SocialBladeV2Status", "SocialChallengeStatus", "SocialGatheringStatus", "SocialChatStatus",
            "AchievementSceneStatus", "TournamentStatus",
            "BoosterStatus"};   // Packs tab: no packs to open, the whole collection is owned from the start

    private static Map<String, Boolean> killSwitchMap() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : KILL_SWITCHES) m.put(k, true);
        return m;
    }

    // ---- StartHook ----------------------------------------------------------------------------------------------

    private Message startHook() {
        long now = System.currentTimeMillis();
        StartHookResponseV2.Builder b = StartHookResponseV2.newBuilder()
                .setInventoryInfo(inventory())
                .setDeckLimit(1000)
                .setKillSwitchNotification(KillSwitchNotification.newBuilder().putAllKillSwitches(killSwitchMap()).putAllUxKillSwitches(killSwitchMap()))
                .setHomePageAchievements(HomePageAchievements.getDefaultInstance())
                .setPreferredCosmetics(PreferredCosmetics.getDefaultInstance())
                .setCardMetadataInfo(CardMetadataInfo.getDefaultInstance())
                .setServerTimeInternal(Timestamp.newBuilder().setSeconds(now / 1000).setNanos((int) (now % 1000) * 1_000_000));
        // The decks MUST come with StartHook: the client initializes its deck cache from this list and considers it
        // fresh (it only calls DeckGetDeckSummariesV3 after something marks it dirty).
        int n = 0;
        for (JsonObject d : decks.all()) {
            try {
                DeckSummaryV3 summary = summaryProto(d.getAsJsonObject("Summary"));
                b.addDeckSummaries(summary);
                b.putDecksInternal(summary.getDeckIdInternal(), deckProto(d.getAsJsonObject("Deck")));
                n++;
            } catch (Exception e) {
                System.out.println("[mtga] FD: skipping unreadable deck " + d.get("Summary") + ": " + e);
            }
        }
        b.setDeckSummariesCacheVersion(1).setDecksCacheVersion(1);
        System.out.println("[mtga] FD StartHook: " + n + " decks");
        return b.build();
    }

    /** Stored DTO_DeckSummaryV3 JSON (as written by the client) -> protobuf DeckSummaryV3 for StartHook. */
    static DeckSummaryV3 summaryProto(JsonObject j) {
        DeckSummaryV3.Builder b = DeckSummaryV3.newBuilder().setDeckIdInternal(str(j, "DeckId"));
        if (str(j, "Mana") != null) b.setMana(str(j, "Mana"));
        if (str(j, "Name") != null) b.setName(str(j, "Name"));
        if (str(j, "Description") != null) b.setDescription(str(j, "Description"));
        JsonElement attrs = j.get("Attributes");
        if (attrs != null && attrs.isJsonArray()) {
            for (JsonElement a : attrs.getAsJsonArray()) {
                JsonObject o = a.getAsJsonObject();
                b.addAttributes(DeckAttribute.newBuilder().setName(nz(str(o, "name"))).setValue(nz(str(o, "value"))));
            }
        }
        if (j.has("DeckTileId") && !j.get("DeckTileId").isJsonNull()) b.setDeckTileId(com.google.protobuf.UInt32Value.of(j.get("DeckTileId").getAsInt()));
        if (j.has("DeckArtId") && !j.get("DeckArtId").isJsonNull()) b.setDeckArtId(com.google.protobuf.UInt32Value.of(j.get("DeckArtId").getAsInt()));
        if (j.has("IsCompanionValid") && !j.get("IsCompanionValid").isJsonNull()) b.setIsCompanionValid(j.get("IsCompanionValid").getAsBoolean());
        if (j.has("IsNetDeck") && !j.get("IsNetDeck").isJsonNull()) b.setIsNetDeck(j.get("IsNetDeck").getAsBoolean());
        if (str(j, "NetDeckFolderId") != null) b.setNetDeckFolderIdInternal(com.google.protobuf.StringValue.of(str(j, "NetDeckFolderId")));
        JsonElement pc = j.get("PreferredCosmetics");
        if (pc != null && pc.isJsonObject()) {
            JsonObject o = pc.getAsJsonObject();
            PreferredCosmeticsOnDeck.Builder cb = PreferredCosmeticsOnDeck.newBuilder()
                    .setAvatar(nz(str(o, "Avatar"))).setSleeve(nz(str(o, "Sleeve"))).setPet(nz(str(o, "Pet"))).setTitle(nz(str(o, "Title")));
            JsonElement em = o.get("Emotes");
            if (em != null && em.isJsonArray()) for (JsonElement e : em.getAsJsonArray()) cb.addEmotes(e.getAsString());
            b.setPreferredCosmetics(cb);
        }
        return b.build();
    }

    /** Stored Deck JSON -> protobuf Deck. */
    static Deck deckProto(JsonObject j) {
        Deck.Builder b = Deck.newBuilder();
        b.addAllMainDeck(cards(j.get("MainDeck")));
        b.addAllSideboard(cards(j.get("Sideboard")));
        b.addAllCommandZone(cards(j.get("CommandZone")));
        b.addAllCompanions(cards(j.get("Companions")));
        JsonElement skins = j.get("CardSkins");
        if (skins != null && skins.isJsonArray()) {
            for (JsonElement e : skins.getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                b.addCardSkins(CardSkin.newBuilder().setGrpId(o.get("GrpId").getAsLong()).setCCV(nz(str(o, "CCV"))));
            }
        }
        return b.build();
    }

    private static java.util.List<DeckCard> cards(JsonElement list) {
        java.util.List<DeckCard> out = new java.util.ArrayList<>();
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement e : list.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(DeckCard.newBuilder().setCardId(o.get("cardId").getAsInt()).setQuantity(o.get("quantity").getAsInt()).build());
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s;
    }

    static InventoryInfo inventory() {
        return InventoryInfo.newBuilder().setSeqId(1).setGems(0).setGold(0)
                .setWildCardCommons(0).setWildCardUnCommons(0).setWildCardRares(0).setWildCardMythics(0)
                .setCosmetics(CosmeticsClient.getDefaultInstance()).build();
    }

    // ---- collection: every collectible printing Forge can play, x4 ----------------------------------------------

    private synchronized String collection() {
        if (collectionJson == null) {
            JsonObject cards = new JsonObject();
            int total = 0, skipped = 0;
            Map<String, Boolean> forgeHas = new HashMap<>();
            for (CardDb.Printing p : db.printings()) {
                boolean has = forgeHas.computeIfAbsent(p.name(), n -> StaticData.instance().getCommonCards().getUniqueByName(n) != null);
                if (!has) { skipped++; continue; }
                cards.addProperty(Integer.toString(p.grpId()), 4);
                total++;
            }
            collectionJson = obj("cacheVersion", 1, "cards", cards).toString();
            System.out.println("[mtga] FD collection: " + total + " printings owned x4, " + skipped + " printings unknown to Forge");
        }
        return collectionJson;
    }

    // ---- formats ------------------------------------------------------------------------------------------------

    // Legality follows Forge's format definitions (ForgeFormats); the bot-match event uses the permissive "every card"
    // format so any deck can be picked there.
    private synchronized GetFormatsResponse formats() {
        if (formats == null) formats = forgeFormats().response();
        return formats;
    }

    private ForgeFormats forgeFormats;

    synchronized ForgeFormats forgeFormats() {
        if (forgeFormats == null) forgeFormats = new ForgeFormats(db);
        return forgeFormats;
    }

    // ---- preferences --------------------------------------------------------------------------------------------

    private String prefsJson() {
        JsonObject m = new JsonObject();
        synchronized (prefs) { prefs.forEach(m::addProperty); }
        return obj("Preferences", m).toString();
    }

    private void loadPrefs() {
        try {
            if (!prefsFile.isFile()) return;
            JsonObject o = JsonParser.parseString(Files.readString(prefsFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) prefs.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot read " + prefsFile + ": " + e);
        }
    }

    private void savePrefs() {
        try {
            JsonObject o = new JsonObject();
            prefs.forEach(o::addProperty);
            prefsFile.getParentFile().mkdirs();
            Files.writeString(prefsFile.toPath(), o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot write " + prefsFile + ": " + e);
        }
    }

    // ---- json helpers -------------------------------------------------------------------------------------------

    static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v == null) o.add(k, com.google.gson.JsonNull.INSTANCE);
            else if (v instanceof JsonElement je) o.add(k, je);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean bo) o.addProperty(k, bo);
            else o.addProperty(k, v.toString());
        }
        return o;
    }

    static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
