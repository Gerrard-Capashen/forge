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
import forge.mtga.profile.PublicProfile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final PremierDrafts premier;
    private final PlayQueues queues = new PlayQueues();
    private final ProfileApi profile;
    private final Map<String, Handler> handlers = new HashMap<>();
    private String collectionJson;
    private GetFormatsResponse formats;
    private volatile FdSession current;

    public FrontDoor(CardDb db, File dataDir, ProfileApi profile) {
        this.db = db;
        this.decks = new DeckStore(new File(dataDir, "decks"));
        this.events = new DraftEvents(db, dataDir);
        this.profile = profile;
        this.premier = new PremierDrafts(db, events, profile);
        seedStarterDecks(dataDir);
        register();
        System.out.println("[mtga] FD data dir " + dataDir.getAbsolutePath() + " (" + decks.all().size() + " decks)");
    }

    public DeckStore decks() { return decks; }

    public ProfileApi profile() { return profile; }

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
        Handler session = (s, r) -> {
            String who = str(r.obj(), "PlayerId");
            if (who != null && !who.equals(profile.profile().playerId)) System.out.println("[mtga] FD: client authenticated as " + who + ", profile is " + profile.profile().playerId);
            return obj("SessionId", "forge-local-session", "Attached", true, "PlayerConditions", 0);
        };
        on("Authenticate", session);
        on("Attach", session);

        on("StartHook", (s, r) -> startHook());
        on("CardGetAllCards", (s, r) -> collection());
        on("GetFormats", (s, r) -> formats());
        on("GetDesignerMetadata", (s, r) -> obj("NonCraftableCardList", new JsonArray(), "NonCollectibleCardList", new JsonArray(),
                "UnreleasedSets", new JsonArray()));
        on("GetKillSwitchData", (s, r) -> {
            JsonObject ks = new JsonObject();
            for (String k : KILL_SWITCHES) ks.addProperty(k, killSwitch(k));
            return obj("KillSwitches", ks, "UxKillSwitches", ks.deepCopy());
        });
        on("EventGetActiveMatches", (s, r) -> obj("MatchesV3", new JsonArray()));

        // player preferences: a string map, part of the player's profile document
        on("GetPlayerPreferences", (s, r) -> prefsJson());
        on("SetPlayerPreferences", (s, r) -> {
            JsonObject p = r.obj().has("Preferences") && r.obj().get("Preferences").isJsonObject()
                    ? r.obj().getAsJsonObject("Preferences") : new JsonObject();
            JsonElement map = p.get("Preferences");
            if (map != null && map.isJsonObject()) {
                profile.store().update(pr -> {
                    for (Map.Entry<String, JsonElement> e : map.getAsJsonObject().entrySet()) {
                        pr.preferences.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                    }
                });
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
            return d == null ? new Err(404, "no deck " + id) : primaryPrintings(d.getAsJsonObject("Deck").deepCopy()).toString();
        });
        on("DeckUpsertDeckV3", (s, r) -> {
            JsonObject summary = r.obj().getAsJsonObject("Summary");
            JsonObject deck = r.obj().getAsJsonObject("Deck");
            String id = str(summary, "DeckId");
            if (id == null || deck == null) return new Err(400, "bad upsert");
            decks.put(id, summary, primaryPrintings(deck));
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

        // ---- play: Bot Play (vs the Forge AI) and Casual Play (vs another player) in Standard, Pioneer and Modern, see PlayQueues ----
        on("GetPlayBladeQueueConfig", (s, r) -> queues.queueConfig().toString());
        on("EventGetActiveEventsV2", (s, r) -> {
            JsonArray bots = new JsonArray();
            bots.add(botEvent(BOT_EVENT, ForgeFormats.ANY, 100));
            bots.add(botEvent(BOT_EVENT + "_Rebalanced", "Alchemy", 99));
            return obj("DynamicFilterTags", new JsonArray(), "CacheVersion", 1, "Events", allEvents(),
                    "Challenges", challenges != null ? new JsonArray() : null,     // non-null makes the client build its private game event (the challenge screen's deck selector needs it)
                    "AiBotMatches", bots);
        });
        on("EventGetCoursesV2", (s, r) -> obj("Courses", events.courseList()));

        // ---- quick drafts: one event per Forge set, bot pod run by Forge (see DraftEvents) ----
        on("EventJoin", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.join(str(r.obj(), "EventName"))
                : PremierDrafts.isPremier(str(r.obj(), "EventName")) ? premier.join(r.obj()) : events.join(r.obj()));
        on("BotDraftStartDraft", (s, r) -> events.startDraft(r.obj()));
        on("BotDraftDraftStatus", (s, r) -> events.draftStatus(r.obj()));
        on("BotDraftDraftPick", (s, r) -> events.draftPick(r.obj()));
        on("EventSetDeckV3", (s, r) -> setEventDeck(r.obj()));
        on("EventDrop", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.drop(str(r.obj(), "EventName"), true) : dropDraft(r.obj(), true));
        on("EventResign", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.drop(str(r.obj(), "EventName"), false) : dropDraft(r.obj(), false));
        // ---- premier drafts: table draft through the client's human-draft flow (see PremierDrafts) ----
        on("EventJoinDraftQueue", (s, r) -> premier.joinQueue(s::push, r.obj()));
        on("EventLeaveDraftQueue", (s, r) -> premier.leaveQueue());
        on("EventPlayerDraftReadyPlayer", (s, r) -> premier.ready(r.obj()));
        on("EventJoinDraft", (s, r) -> premier.rejoin(r.obj()));
        on("EventPlayerDraftMakePick", (s, r) -> premier.pick(r.obj()));
        on("EventPlayerDraftReserveCard", (s, r) -> premier.reserve(r.obj(), false));
        on("EventPlayerDraftClearReservedCard", (s, r) -> premier.reserve(r.obj(), true));
        on("EventPlayerDraftGetTablePacks", (s, r) -> premier.tablePacks(r.obj()));
        on("DraftCompleteDraft", (s, r) -> premier.complete(r.obj()));
        on("EventPlayerDraftConfirmCardPoolGrant", (s, r) -> premier.confirmPool(r.obj()));
        on("EventEnterPairing", (s, r) -> startEventMatch(s, str(r.obj(), "EventName")));
        on("EventAiBotMatch", (s, r) -> startBotMatch(s, r.obj()));
        on("EventLeavePairing", (s, r) -> {      // the client left the "waiting for a match" screen
            Peer p = peer;
            if (p != null && p.host() != null) p.host().cancel();
            leaveOnlineQueue();
            return GSON.toJson("Success");
        });
        // after a match: no rewards/rank/quests offline, just "found" so the end screen continues to the home page
        on("EventGetMatchResultReport", (s, r) -> obj("CurrentModule", lastEvent != null ? events.module(lastEvent) : "Complete", "FoundMatch", true, "InventoryInfo", null,
                "questUpdates", new JsonArray(), "rankUpdates", null, "mythicRatingUpdates", null, "periodicRewardsProgress", null));

        // ---- neutral answers so the home page has no errors (no store, quests, rank, rewards, social offline) ----
        on("StaticContent", (s, r) -> profile.staticContent());
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
        // No store offline (MercGetStoreStatusV2 says StoreEnabled=false, which keeps the Store tab locked), but the client also builds
        // the cosmetic catalogues (profile pickers, deck cosmetics, emotes) from this call: answer it with every listing type EMPTY.
        on("MercGetSkusAndListings", (s, r) -> {
            JsonObject versions = new JsonObject(), listings = new JsonObject();
            for (String t : new String[] {"Card", "Pet", "ArtStyle", "Avatar", "Sleeve", "Booster", "SetMastery", "Bundle", "Sale", "Economic",
                    "Emote", "PreconDeck", "PrizeWall"}) {
                versions.addProperty(t, "1");
                listings.add(t, new JsonArray());
            }
            return obj("SkusCacheVersionHash", "1", "Skus", new JsonObject(), "ListingCacheVersionsHash", versions, "Listings", listings);
        });
        // no season payouts offline: a plain error makes the client skip the payout popup
        on("RankEvaluatePayoutsV2", (s, r) -> new Err(ERR_NOT_IMPLEMENTED, "no season payouts offline"));
        on("StoreGetEntitlementsV2", (s, r) -> obj("InventoryInfo", null, "ContainsFirstPurchase", false));
        on("GetVoucherDefinitions", (s, r) -> GetVoucherDefinitionsResponse.getDefaultInstance());
        on("GetPlayerInbox", (s, r) -> GetPlayerInboxResp.getDefaultInstance());
        on("GetWpnNotificationPreferences", (s, r) -> {
            WpnNotificationPreference off = WpnNotificationPreference.getDefaultInstance();
            return GetWpnNotificationPreferencesResp.newBuilder().setDefaultWpnNotificationPreference(off).setGlobalNotificationPreference(off).build();
        });
        // direct challenges: through the coordinator when online play is on (ChallengeService), else nothing to reconnect and no challenges
        for (String cmd : ChallengeService.COMMANDS) {
            on(cmd, (s, r) -> challenges != null ? challenges.handle(cmd, s, r)
                    : "ChallengeReconnectAll".equals(cmd) ? ChallengeReconnectAllResp.getDefaultInstance() : new Err(7000, "challenges need online play"));
        }
        // ---- cosmetics: catalogue (StaticContent), owned and equipped, all from the player's profile ----
        on("CosmeticsGetPlayerOwnedCosmetics", (s, r) -> GetPlayerOwnedCosmeticsResp.newBuilder().setOwnedCosmetics(profile.owned()).build());
        on("CosmeticsGetPreferredCosmetics", (s, r) -> profile.preferred());
        on("CosmeticsSetPreferredAvatar", (s, r) -> profile.setAvatar(unpack(r, SetPreferredAvatarCosmeticRequest.class).getId()));
        on("CosmeticsSetPreferredSleeve", (s, r) -> profile.setSleeve(unpack(r, SetPreferredSleeveCosmeticRequest.class).getId()));
        on("CosmeticsSetPreferredPet", (s, r) -> profile.setPet(unpack(r, SetPreferredPetCosmeticRequest.class).getId()));
        on("CosmeticsSetPreferredTitle", (s, r) -> profile.setTitle(unpack(r, SetPreferredTitleCosmeticRequest.class).getId()));
        on("CosmeticsSetPreferredEmotes", (s, r) -> profile.setEmotes(unpack(r, SetPreferredEmotesCosmeticRequest.class).getIdsList()));
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
    /** {@code coordinator} != null = online matchmaking (ws(s):// address of the coordinator); {@code friendCode} limits the pairing to players with the same code. */
    public record Peer(forge.mtga.peer.PeerHost host, String joinHost, int joinPort, String key, String fingerprint, String coordinator, String friendCode) {
        public Peer(forge.mtga.peer.PeerHost host, String joinHost, int joinPort, String key, String fingerprint) { this(host, joinHost, joinPort, key, fingerprint, null, ""); }
    }

    private volatile Peer peer;
    private volatile ChallengeService challenges;

    /** Direct challenges need the coordinator's friends: set by the server main when online play is on. */
    public void setSocial(forge.mtga.social.SocialClient social) {
        this.social = social;
        challenges = social == null ? null : new ChallengeService(this, social);
    }

    private volatile forge.mtga.social.SocialClient social;

    /** Orange name: this player from the profile (copied from the coordinator), an opponent asked of the coordinator (never taken from what a peer sends about itself). */
    private boolean isDev(PublicProfile p) {
        if (p.playerId().equals(profile.self().playerId())) return profile.isDev();
        forge.mtga.social.SocialClient sc = social;
        return sc != null && sc.isDev(p.playerId());
    }

    FdSession session() { return current; }

    Peer peer() { return peer; }

    public void setPeer(Peer p) { peer = p; }

    private volatile forge.mtga.social.ResultReporter results;

    /** Online matches report how they ended to the coordinator (set by the server main when online play is on). */
    public void setResults(forge.mtga.social.ResultReporter r) { results = r; }

    /** What a seat of this online match tells when it ends: the result goes to the coordinator (nothing without an account). */
    private java.util.function.Consumer<String> outcomeReport(forge.mtga.peer.Coordinator.Paired paired, String pool) {
        forge.mtga.social.ResultReporter r = results;
        boolean event = DraftEvents.isDraftEvent(pool);
        if (r == null && !event) return null;
        return outcome -> {
            if (event && ("win".equals(outcome) || "loss".equals(outcome))) events.recordResult(pool, "win".equals(outcome));
            if (r != null) r.report(paired, pool, outcome);
        };
    }

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

    private JsonArray allEvents() {
        JsonArray all = queues.eventInfos();
        all.addAll(premier.eventInfos());
        all.addAll(events.eventInfos());
        return all;
    }

    /** EventSetDeckV3: a queue event checks the deck against its format first; draft events keep their own course. */
    private Object setEventDeck(JsonObject req) {
        String name = str(req, "EventName");
        if (!PlayQueues.isQueueEvent(name)) return events.setDeck(req);
        JsonObject deck = req.has("Deck") && req.get("Deck").isJsonObject() ? req.getAsJsonObject("Deck") : null;
        String problem = deck == null ? "no deck submitted" : deckProblem(PlayQueues.formatOf(name), deck);
        if (problem != null) System.out.println("[mtga] FD " + name + ": deck refused: " + problem);
        return queues.setDeck(name, req, problem);
    }

    /** Why the main deck (client JSON) is not playable in a client format, null if it is. */
    String deckProblem(String format, JsonObject deck) {
        Map<String, Integer> names = new LinkedHashMap<>();
        int unknown = 0;
        JsonArray main = deck.getAsJsonArray("MainDeck");
        if (main != null) {
            for (JsonElement e : main) {
                JsonObject c = e.getAsJsonObject();
                String n = db.name(c.get("cardId").getAsInt());
                if (n == null || StaticData.instance().getCommonCards().getUniqueByName(n) == null) unknown += c.get("quantity").getAsInt();
                else names.merge(n, c.get("quantity").getAsInt(), Integer::sum);
            }
        }
        return forgeFormats().problem(format, names, unknown);
    }

    /** A client-JSON deck as Forge card names with counts and the client printing each name has (cards Forge does not know are left out). */
    record Cards(Map<String, Integer> human, Map<String, Integer> printing) { }

    Cards cardsOf(JsonObject deck) {
        Map<String, Integer> human = new LinkedHashMap<>();
        Map<String, Integer> printing = new HashMap<>();
        JsonArray main = deck.getAsJsonArray("MainDeck");
        if (main != null) {
            for (JsonElement e : main) {
                JsonObject c = e.getAsJsonObject();
                int grp = c.get("cardId").getAsInt(), qty = c.get("quantity").getAsInt();
                String name = db.name(grp);
                if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) continue;
                human.merge(name, qty, Integer::sum);
                printing.putIfAbsent(name, grp);
            }
        }
        return new Cards(human, printing);
    }

    /** EventEnterPairing: a Bot Play / Casual Play queue starts its match; for a draft event play the course's deck against one of the bots' drafted decks. */
    private Object startEventMatch(FdSession s, String eventName) {
        if (PlayQueues.isQueueEvent(eventName)) {
            JsonObject c = queues.course(eventName);
            if (c == null || !c.has("CourseDeck") || !c.get("CourseDeck").isJsonObject() || c.getAsJsonObject("CourseDeck").getAsJsonArray("MainDeck").size() == 0) {
                return new Err(7000, "no deck for event " + eventName);
            }
            return startMatch(s, c.getAsJsonObject("CourseDeckSummary"), c.getAsJsonObject("CourseDeck"), null, eventName);
        }
        JsonObject course = DraftEvents.isDraftEvent(eventName) ? events.course(eventName) : null;
        if (course == null || !course.has("CourseDeck") || !course.get("CourseDeck").isJsonObject()) return new Err(7000, "no deck for event " + eventName);
        Peer p = peer;
        if (PremierDrafts.isPremier(eventName) && p != null && p.coordinator() != null) {
            Cards cards = cardsOf(course.getAsJsonObject("CourseDeck"));
            if (cards.human().isEmpty()) return new Err(7000, "no deck for event " + eventName);
            lastEvent = eventName;
            return startOnlineMatch(s, str(course.getAsJsonObject("CourseDeckSummary"), "Name"), cards.human(), cards.printing(),
                    profile.forDeck(course.getAsJsonObject("CourseDeckSummary")), eventName, p);
        }
        Map<String, Integer> bot = events.botDeck(eventName);
        forge.mtga.AiDecks.Choice ai = bot == null ? null : new forge.mtga.AiDecks.Choice("draft bot", bot);
        return startMatch(s, course.getAsJsonObject("CourseDeckSummary"), course.getAsJsonObject("CourseDeck"), ai, eventName);
    }

    /** EventDrop / EventResign of a draft event (a premier draft's table closes too). */
    private Object dropDraft(JsonObject req, boolean wrapped) {
        premier.dropped(str(req, "EventName"));
        return events.drop(req, wrapped);
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

        PublicProfile me = profile.forDeck(summary);
        PlayQueues.Mode mode = PlayQueues.modeOf(eventId);
        String queueFormat = PlayQueues.formatOf(eventId);
        if (mode != null) {
            String problem = deckProblem(queueFormat, deck);   // the client only offers legal decks; this is the server's own check
            if (problem != null) return new Err(7000, PlayQueues.queueLabel(eventId) + ": " + problem);
        }
        if (mode == PlayQueues.Mode.CASUAL) {
            if (peer == null) return new Err(7000, "Casual Play needs online matchmaking, which is switched off in this start (-Dmtga.p2p=off)");
            return startPeerMatch(s, deckName, human, printing, me, eventId);
        }
        forge.mtga.AiDecks.Choice ai = aiOverride != null ? aiOverride : aiDeck(mode == PlayQueues.Mode.BOT ? queueFormat : deckFormat(summary));
        String matchId = "forge-" + System.currentTimeMillis() + "-" + (++matchCounter);
        String aiName = "Forge AI";
        boolean event = DraftEvents.isDraftEvent(eventId);
        lastEvent = event ? eventId : null;
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, ai.cards(), printing, aiName, null,
                event ? won -> events.recordResult(eventId, won) : null));
        int total = human.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("[mtga] FD bot match " + matchId + " (" + eventId + "): '" + deckName + "' (" + total + " cards) vs AI deck '" + ai.name() + "'");
        Capture.log("FD bot match " + matchId + " deck '" + deckName + "' vs '" + ai.name() + "'");

        pushMatchCreated(s, matchId, me, profile.forAi(aiName + " (" + ai.name() + ")"), eventId);
        return GSON.toJson(matchId);
    }

    /**
     * Player vs player instead of a bot match (-Dmtga.p2p). Host: this client's seat is built now and the client waits in its
     * queue screen until a guest joins. Guest: connects to the host, then plays through the host's engine.
     * Either way the client's own Match Door connection (127.0.0.1) is what it plays on. The two servers swap the PUBLIC part
     * of the players' profiles in the peer handshake, so each client draws the other's avatar, sleeve, pet, title and emotes.
     */
    private Object startPeerMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId) {
        Peer p = peer;
        if (p.coordinator() != null) return startOnlineMatch(s, deckName, human, printing, me, eventId, p);
        if (p.host() != null) {
            String matchId = "p2p-" + System.currentTimeMillis() + "-" + (++matchCounter);
            forge.mtga.forge.ForgeMatch room = new forge.mtga.forge.ForgeMatch(db, matchId);
            forge.mtga.forge.ForgeBackend mine = new forge.mtga.forge.ForgeBackend(db, room, 0, new forge.mtga.forge.ForgeMatch.Seat(me.name(), human, printing));
            forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, Map.of(), printing, "", mine));
            System.out.println("[mtga] FD P2P host: '" + deckName + "' waits for a guest (match " + matchId + ")");
            p.host().offer(new forge.mtga.peer.PeerHost.Offer(matchId, room, me, eventId, guest -> pushMatchCreated(s, matchId, me, profile.sanitize(guest), eventId)));
            return GSON.toJson(matchId);
        }
        forge.mtga.peer.PeerGuest.Joined j = forge.mtga.peer.PeerGuest.join(p.joinHost(), p.joinPort(), p.key(), p.fingerprint(), eventId, me, human, printing);
        if (j.error() != null) return new Err(7000, "P2P: " + j.error());
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(j.matchId(), human, Map.of(), printing, "", j.backend()));
        System.out.println("[mtga] FD P2P guest: '" + deckName + "' vs " + j.host().name() + " (match " + j.matchId() + ")");
        pushMatchCreated(s, j.matchId(), me, profile.sanitize(j.host()), eventId);
        return GSON.toJson(j.matchId());
    }

    private volatile forge.mtga.peer.Coordinator.Ticket onlineTicket;

    private void leaveOnlineQueue() {
        forge.mtga.peer.Coordinator.Ticket t = onlineTicket;
        onlineTicket = null;
        if (t != null) t.cancel();
    }

    /**
     * Online player vs player: the client waits in its queue screen while the coordinator looks for a second player of the same
     * queue (event) and card database. The one who waited longest hosts: its engine runs the game and the other server joins
     * through the coordinator's relay room instead of a TCP connection; from there it is the same as a P2P match.
     */
    private Object startOnlineMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId, Peer p) {
        leaveOnlineQueue();
        forge.mtga.peer.Coordinator.Ticket ticket = forge.mtga.peer.Coordinator.queue(p.coordinator(), eventId, p.fingerprint(),
                forge.mtga.peer.PeerGuest.VERSION, me.playerId(), me.name(), p.friendCode());
        onlineTicket = ticket;
        try {
            ticket.opened().get(8, java.util.concurrent.TimeUnit.SECONDS);      // fail now, not after a silent wait
        } catch (Exception e) {
            leaveOnlineQueue();
            Throwable c = e.getCause() != null ? e.getCause() : e;
            System.out.println("[mtga] FD online: matchmaking server unreachable (" + c.getMessage() + ")");
            return new Err(7000, "Can't reach the matchmaking server. Check your internet connection and try again.");
        }
        System.out.println("[mtga] FD online: '" + deckName + "' queues in " + eventId + (p.friendCode().isBlank() ? "" : " with friend code " + p.friendCode()));
        ticket.result().whenComplete((paired, err) -> {
            if (err != null) {
                if (!ticket.result().isCancelled()) System.out.println("[mtga] FD online: no match (" + err.getMessage() + ")");
                return;
            }
            Thread t = new Thread(() -> playOnline(s, paired, human, printing, me, eventId, p), "online-match");
            t.setDaemon(true);
            t.start();
        });
        return GSON.toJson("online-" + System.currentTimeMillis() + "-" + (++matchCounter));
    }

    void playOnline(FdSession s, forge.mtga.peer.Coordinator.Paired paired, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId, Peer p) {
        String matchId = paired.match();
        System.out.println("[mtga] FD online: paired with " + paired.opponent() + " as " + paired.role() + ", match " + matchId);
        forge.mtga.peer.PeerLink link = null;
        try {
            if (paired.host()) {
                forge.mtga.forge.ForgeMatch room = new forge.mtga.forge.ForgeMatch(db, matchId);
                forge.mtga.forge.ForgeBackend mine = new forge.mtga.forge.ForgeBackend(db, room, 0, new forge.mtga.forge.ForgeMatch.Seat(me.name(), human, printing));
                forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, Map.of(), printing, "", mine));
                mine.setOutcomeListener(outcomeReport(paired, eventId));
                p.host().offer(new forge.mtga.peer.PeerHost.Offer(matchId, room, me, eventId, guest -> pushMatchCreated(s, matchId, me, profile.sanitize(guest), eventId)));
                link = forge.mtga.peer.Coordinator.openRoom(paired);
                p.host().serveOnline(link);              // returns when the guest has left
                return;
            }
            link = forge.mtga.peer.Coordinator.openRoom(paired);
            forge.mtga.peer.PeerGuest.Joined j = forge.mtga.peer.PeerGuest.joinOnline(link, p.fingerprint(), eventId, me, human, printing);
            if (j.error() != null) {
                System.out.println("[mtga] FD online: join failed: " + j.error());
                return;
            }
            j.backend().setOutcomeListener(outcomeReport(paired, eventId));
            forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(j.matchId(), human, Map.of(), printing, "", j.backend()));
            pushMatchCreated(s, j.matchId(), me, profile.sanitize(j.host()), eventId);
        } catch (Exception e) {
            System.out.println("[mtga] FD online: " + e);
            if (link != null) link.close();
        }
    }

    /**
     * MatchCreated for this client: it is seat 1 on the Match Door of this process, the opponent seat 2. Each player carries
     * the cosmetics the client draws (avatar, sleeve, pet, title, emotes) in {@code CosmeticsSelection}.
     */
    private void pushMatchCreated(FdSession s, String matchId, PublicProfile me, PublicProfile opponent, String eventId) {
        JsonArray players = new JsonArray();
        players.add(playerInfo(me, 1, 1));
        players.add(playerInfo(opponent, 2, 2));
        JsonObject info = obj("MatchEndpointHost", matchHost, "MatchEndpointPort", matchPort, "MatchId", matchId,
                "McFabricId", "fabric:/forge/" + matchId, "EventId", eventId, "Battlefield", "", "MatchType", "Queue",
                "YourSeat", 1, "ClientMetadata", obj(), "PlayerInfos", players);
        String push = obj("Type", "MatchCreated", "MatchInfoV4", info).toString();
        LATER.schedule(() -> {
            Capture.log("FD push MatchCreated " + Capture.flat(push, 600));
            s.push(push);
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private JsonObject playerInfo(PublicProfile p, int seat, int team) {
        return obj("ScreenName", p.name(), "RankingClass", p.rankClass(), "RankingTier", p.rankTier(), "SeatId", seat, "TeamId", team,
                "IsWotc", isDev(p), "CosmeticsSelection", profile.selectionJson(p));
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
            forge.mtga.AiDecks.Choice builtIn = forge.mtga.AiDecks.fromDefaults(format, this::playable);
            if (builtIn != null) return builtIn;
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

    /** Marker of the built-in deck set; a new set (new name) is seeded once on the next start. */
    private static final String SEED_MARKER = ".seeded-defaults-v3";

    /** Can the built-in deck be played as its own format: every card on the client and legal in the format? Reason in the log. */
    private boolean playable(forge.mtga.DefaultDecks.Entry d) {
        for (String n : d.main().keySet()) {
            if (db.grpId(n) == 0) { System.out.println("[mtga] FD built-in deck " + d.format() + " / " + d.name() + " skipped: client has no card " + n); return false; }
        }
        String problem = forgeFormats().problem(d.format(), d.main(), 0);
        if (problem != null) System.out.println("[mtga] FD built-in deck " + d.format() + " / " + d.name() + " skipped: " + problem);
        return problem == null;
    }

    /**
     * First start with this deck set (marker file data/.seeded-defaults-v3): the player gets the built-in Standard, Pioneer and
     * Modern decks (DefaultDecks). The earlier starter decks ("Forge: <precon>", Timeless) are removed if the player never
     * changed them. Delete the marker to seed again.
     */
    private void seedStarterDecks(File dataDir) {
        File marker = new File(dataDir, SEED_MARKER);
        if (marker.exists()) return;
        File old = new File(dataDir, ".seeded");
        if (old.exists()) {
            int removed = 0;
            for (JsonObject d : new java.util.ArrayList<>(decks.all())) {
                JsonObject summary = d.getAsJsonObject("Summary");
                String name = str(summary, "Name"), updated = lastUpdated(summary);
                try {
                    boolean untouched = updated != null && java.time.OffsetDateTime.parse(updated).toInstant().toEpochMilli() <= old.lastModified() + 60_000;
                    if (name != null && name.startsWith("Forge: ") && untouched && decks.delete(str(summary, "DeckId"))) removed++;
                } catch (Exception e) {
                    // unreadable timestamp: leave the deck alone
                }
            }
            old.delete();
            System.out.println("[mtga] FD removed " + removed + " old starter decks");
        }
        int made = 0;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (JsonObject d : decks.all()) have.add(str(d.getAsJsonObject("Summary"), "Name"));
        for (forge.mtga.DefaultDecks.Entry d : forge.mtga.DefaultDecks.all()) {
            try {
                String name = d.name() + " (" + d.format() + ")";
                if (!have.contains(name) && playable(d) && saveDeck(name, d.format(), d.main(), d.side())) made++;
            } catch (Exception e) {
                System.out.println("[mtga] FD: cannot seed " + d.name() + ": " + e);
            }
        }
        try {
            marker.getParentFile().mkdirs();
            Files.writeString(marker.toPath(), "built-in decks seeded: " + made + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot write " + marker + ": " + e);
        }
        System.out.println("[mtga] FD seeded " + made + " built-in decks");
    }

    private static String lastUpdated(JsonObject summary) {
        JsonElement attrs = summary.get("Attributes");
        if (attrs == null || !attrs.isJsonArray()) return null;
        for (JsonElement a : attrs.getAsJsonArray()) {
            JsonObject o = a.getAsJsonObject();
            if ("LastUpdated".equals(str(o, "name"))) return str(o, "value").replace("\"", "");
        }
        return null;
    }

    private JsonArray cardList(Map<String, Integer> cards, String deckName, int[] totals) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            int grp = db.grpId(e.getKey());
            if (grp == 0) {
                System.out.println("[mtga] FD: '" + deckName + "': client has no card " + e.getKey());
                continue;
            }
            out.add(obj("cardId", grp, "quantity", e.getValue()));
            totals[0] += e.getValue();
            boolean basic = e.getKey().matches("Plains|Island|Swamp|Mountain|Forest|Wastes");
            if (totals[1] == 0 && !basic) totals[1] = grp;   // deck tile: first non-basic card
        }
        return out;
    }

    /**
     * The deck with every card as its primary printing: the client owns and legalises only those, so a deck holding another printing
     * (client imports pick them by set code) would show missing wildcards and lose cards to "not legal in this format".
     */
    private JsonObject primaryPrintings(JsonObject deck) {
        for (String pile : new String[] {"MainDeck", "Sideboard", "CommandZone", "Companions"}) {
            if (!deck.has(pile) || !deck.get(pile).isJsonArray()) continue;
            for (JsonElement e : deck.getAsJsonArray(pile)) {
                JsonObject card = e.getAsJsonObject();
                if (!card.has("cardId")) continue;
                int grp = card.get("cardId").getAsInt(), primary = db.primaryPrinting(grp);
                if (primary != grp) System.out.println("[mtga] FD deck card " + grp + " -> primary printing " + primary);
                card.addProperty("cardId", primary);
            }
        }
        return deck;
    }

    /** Store a deck given by Forge card names, in the same JSON shape the client writes (DeckUpsertDeckV3). */
    private boolean saveDeck(String name, String format, Map<String, Integer> cards, Map<String, Integer> side) throws java.io.IOException {
        int[] totals = new int[2];   // cards, tile
        JsonArray main = cardList(cards, name, totals);
        JsonArray sideboard = cardList(side, name, new int[2]);
        int total = totals[0], tile = totals[1];
        if (total < 40) return false;
        String id = java.util.UUID.randomUUID().toString();
        String now = java.time.OffsetDateTime.now().toString();
        JsonArray attrs = new JsonArray();
        attrs.add(obj("name", "Version", "value", "11"));
        attrs.add(obj("name", "TileID", "value", Integer.toString(tile)));
        attrs.add(obj("name", "LastPlayed", "value", "\"0001-01-01T00:00:00\""));
        attrs.add(obj("name", "LastUpdated", "value", "\"" + now + "\""));
        attrs.add(obj("name", "IsFavorite", "value", "false"));
        attrs.add(obj("name", "Format", "value", format));
        JsonObject summary = obj("DeckId", id, "Mana", "", "Name", name, "Attributes", attrs, "Description", null,
                "DeckTileId", tile, "DeckArtId", db.artId(tile), "IsCompanionValid", false,
                "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
        JsonObject deck = obj("MainDeck", main, "Sideboard", sideboard, "CommandZone", new JsonArray(),
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
            "AchievementSceneStatus", "TournamentStatus", "StoreStatus",     // StoreStatus: no store offline (its listings are empty; the cosmetic catalogues come from the profile, see CosmeticCatalogs.cs)
            "BoosterStatus"};   // Packs tab: no packs to open, the whole collection is owned from the start

    private static Map<String, Boolean> killSwitchMap() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : KILL_SWITCHES) m.put(k, killSwitch(k));
        return m;
    }

    /** The client's friends / chat / challenge screens: on (switch false) with -Dmtga.social=online (the coordinator's friends, set at start when online play is on) or spike (fake friends). */
    private static final java.util.Set<String> SOCIAL_SWITCHES = java.util.Set.of("HasbroGoAddRemoveFriends", "SocialBladeStatus",
            "SocialChallengeStatus", "SocialGatheringStatus", "SocialChatStatus");   // SocialBladeV2Status stays off: the client then builds its classic SocialUI (V2 loads another scene)

    private static boolean killSwitch(String name) {
        String mode = System.getProperty("mtga.social", "off");
        return !(("spike".equals(mode) || "online".equals(mode)) && SOCIAL_SWITCHES.contains(name));
    }

    // ---- StartHook ----------------------------------------------------------------------------------------------

    private Message startHook() {
        long now = System.currentTimeMillis();
        StartHookResponseV2.Builder b = StartHookResponseV2.newBuilder()
                .setInventoryInfo(inventory())
                .setDeckLimit(1000)
                .setKillSwitchNotification(KillSwitchNotification.newBuilder().putAllKillSwitches(killSwitchMap()).putAllUxKillSwitches(killSwitchMap()))
                .setHomePageAchievements(HomePageAchievements.getDefaultInstance())
                .setPreferredCosmetics(profile.preferred())
                .setCardMetadataInfo(CardMetadataInfo.getDefaultInstance())
                .setServerTimeInternal(Timestamp.newBuilder().setSeconds(now / 1000).setNanos((int) (now % 1000) * 1_000_000));
        // The decks MUST come with StartHook: the client initializes its deck cache from this list and considers it
        // fresh (it only calls DeckGetDeckSummariesV3 after something marks it dirty).
        int n = 0;
        for (JsonObject d : decks.all()) {
            try {
                DeckSummaryV3 summary = summaryProto(d.getAsJsonObject("Summary"));
                b.addDeckSummaries(summary);
                b.putDecksInternal(summary.getDeckIdInternal(), deckProto(primaryPrintings(d.getAsJsonObject("Deck").deepCopy())));
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

    private InventoryInfo inventory() {
        return InventoryInfo.newBuilder().setSeqId(profile.inventorySeq()).setGems(0).setGold(0)
                .setWildCardCommons(0).setWildCardUnCommons(0).setWildCardRares(0).setWildCardMythics(0)
                .setCosmetics(profile.owned()).build();
    }

    private static <T extends Message> T unpack(Req r, Class<T> type) throws Exception { return r.any().unpack(type); }

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
        profile.profile().preferences.forEach(m::addProperty);
        return obj("Preferences", m).toString();
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
