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
    private final ArchenemyEvent archenemy;
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
        this.archenemy = new ArchenemyEvent(dataDir);
        this.profile = profile;
        this.premier = new PremierDrafts(db, events, profile, () -> {
            Peer p = peer;                                     // premier tables are online when online matches are (-Dmtga.premier.online=false: bots only)
            return p == null || p.coordinator() == null || !Boolean.parseBoolean(System.getProperty("mtga.premier.online", "true")) ? null
                    : new PremierDrafts.Online(p.coordinator(), p.fingerprint(), p.friendCode());
        });
        seedStarterDecks(dataDir);
        for (JsonObject d : decks.all()) {                     // decks copied from a draft event before the "Draft" format existed
            JsonObject summary = d.getAsJsonObject("Summary");
            if (summary != null && ownDeckFormat(summary)) {
                try {
                    decks.put(str(summary, "DeckId"), summary, d.getAsJsonObject("Deck"));
                } catch (Exception e) {
                    System.out.println("[mtga] FD: cannot update deck " + str(summary, "Name") + ": " + e);
                }
            }
        }
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
            ownDeckFormat(summary);
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
            if (masteryTrack.enabled() && (requested == null || requested.equals("None") || requested.equals("-1") || requested.equals("SetMastery"))) {
                defs.add(masteryTrack.definition());
            }
            return obj("GraphDefinitions", defs);
        });
        on("GraphGetGraphState", (s, r) -> graphState(str(r.obj(), "GraphId")));
        on("GraphProcessV2", (s, r) -> {
            String graphId = str(r.obj(), "GraphId");
            JsonObject inventory = null;
            if (masteryTrack.enabled() && MasteryTrack.GRAPH_ID.equals(graphId) && r.obj().has("NodeIds") && r.obj().get("NodeIds").isJsonArray()) {
                inventory = masteryTrack.claim(r.obj().getAsJsonArray("NodeIds"));
            }
            JsonObject states = new JsonObject();
            states.add(graphId == null ? "" : graphId, graphState(graphId));
            return obj("GraphStates", states, "InventoryInfo", inventory);
        });

        // ---- play: Bot Play (vs the Forge AI) and Casual Play (vs another player) per format, see PlayQueues ----
        on("GetPlayBladeQueueConfig", (s, r) -> queues.queueConfig(playableModes()).toString());
        on("EventGetActiveEventsV2", (s, r) -> {
            JsonArray bots = new JsonArray();
            bots.add(botEvent(BOT_EVENT, ForgeFormats.ANY, 100));
            bots.add(botEvent(BOT_EVENT + "_Rebalanced", "Alchemy", 99));
            return obj("DynamicFilterTags", new JsonArray(), "CacheVersion", 1, "Events", allEvents(),
                    "Challenges", challenges != null ? new JsonArray() : null,     // non-null makes the client build its private game event (the challenge screen's deck selector needs it)
                    "AiBotMatches", bots);
        });
        on("EventGetCoursesV2", (s, r) -> {
            JsonArray courses = new JsonArray();     // no course of a hidden event (the client fails on a course without its event)
            for (JsonElement c : events.courseList()) if (premierShown() || !PremierDrafts.isPremier(str(c.getAsJsonObject(), "InternalEventName"))) courses.add(c);
            courses.addAll(archenemy.courseList());
            return obj("Courses", courses);
        });

        // ---- quick drafts: one event per Forge set, bot pod run by Forge (see DraftEvents) ----
        on("EventJoin", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.join(str(r.obj(), "EventName"))
                : ArchenemyEvent.is(str(r.obj(), "EventName")) ? archenemy.join()
                : PremierDrafts.isPremier(str(r.obj(), "EventName")) ? premier.join(r.obj()) : events.join(r.obj()));
        on("BotDraftStartDraft", (s, r) -> events.startDraft(r.obj()));
        on("BotDraftDraftStatus", (s, r) -> events.draftStatus(r.obj()));
        on("BotDraftDraftPick", (s, r) -> events.draftPick(r.obj()));
        on("EventSetDeckV3", (s, r) -> setEventDeck(r.obj()));
        on("EventClaimPrize", (s, r) -> {
            premier.dropped(str(r.obj(), "EventName"));
            return events.claimPrize(r.obj());
        });
        on("EventDrop", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.drop(str(r.obj(), "EventName"), true)
                : ArchenemyEvent.is(str(r.obj(), "EventName")) ? archenemy.drop(true) : dropDraft(r.obj(), true));
        on("EventResign", (s, r) -> PlayQueues.isQueueEvent(str(r.obj(), "EventName")) ? queues.drop(str(r.obj(), "EventName"), false)
                : ArchenemyEvent.is(str(r.obj(), "EventName")) ? archenemy.drop(false) : dropDraft(r.obj(), false));
        // ---- premier drafts: table draft through the client's human-draft flow (see PremierDrafts) ----
        on("EventJoinDraftQueue", (s, r) -> premier.joinQueue(s::push, r.obj()));
        on("EventLeaveDraftQueue", (s, r) -> premier.leaveQueue());
        on("EventPlayerDraftReadyPlayer", (s, r) -> premier.ready(r.obj()));
        on("EventJoinDraft", (s, r) -> premier.rejoin(s::push, r.obj()));
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
        // after a match: no rewards/quests, just "found" so the end screen continues to the home page; a ranked match carries its pips
        on("EventGetMatchResultReport", (s, r) -> matchResultReport(str(r.obj(), "MatchId")));

        // ---- neutral answers so the home page has no errors (no store, quests, rank, rewards, social offline) ----
        on("StaticContent", (s, r) -> profile.staticContent());
        // rank: from the coordinator (cached; Bronze 4 offline), see forge.mtga.social.RankService
        on("RankGetCombinedRankInfo", (s, r) -> rank.combinedRankInfo());
        on("RankGetSeasonAndRankDetails", (s, r) -> rank.seasonAndRankDetails());
        on("RenewalGetCurrentRenewal", (s, r) -> obj("currentId", null, "definitionId", null, "initialPayoutsStartTime", "2099-01-01T00:00:00Z",
                "initialPayoutsEndTime", "2099-01-01T00:00:00Z", "rotatingSets", new JsonArray(), "ready", false));
        on("DeckGetAllPreconDecksV3", (s, r) -> obj("CacheVersion", 1, "PreconDecks", new JsonArray()));
        on("GetNetDeckFolders", (s, r) -> "[]");
        on("GetAllPrizeWalls", (s, r) -> obj("ActivePrizeWalls", new JsonArray()));
        on("GetAllPreferredPrintings", (s, r) -> "{}");
        on("MercGetStoreStatusV2", (s, r) -> obj("DisabledTags", new JsonArray(), "DisabledListings", new JsonArray(),
                "CodeRedemptionEnabled", false, "StoreEnabled", false));
        // daily quests from the coordinator (DailyQuests, QuestService); offline: none
        on("QuestGetQuests", (s, r) -> quests.enabled() ? quests.collection() : obj("canSwap", false, "newQuests", 0, "quests", new JsonArray()));
        on("QuestSwapQuest", (s, r) -> quests.enabled() ? quests.swap(str(r.obj(), "QuestId")) : new Err(ERR_NOT_IMPLEMENTED, "no quests offline"));
        on("PeriodicRewardsGetStatus", (s, r) -> obj("_dailyRewardSequenceId", 0, "_dailyRewardResetTimestamp", "2099-01-01T00:00:00Z",
                "_weeklyRewardSequenceId", 0, "_weeklyRewardResetTimestamp", "2099-01-01T00:00:00Z",
                "_dailyRewardChestDescriptions", obj(), "_weeklyRewardChestDescriptions", obj()));
        // home banners: AssetTreeItem picks the client's art, the text keys are ForgeBridge.FormatLoc's. Action types are
        // ECarouselActionType numbers: 4 OpenPlayBlade (argument: a queue event's public name), 3 GoToExternalUrl.
        on("CarouselGetCarouselItems", (s, r) -> {
            JsonArray items = new JsonArray();
            items.add(carouselItem("ForgeWelcome", "2026_61_30_HOB_Previews", 100, 4, PlayQueues.eventName(PlayQueues.Mode.BOT, "Standard")));
            items.add(carouselItem("ForgeDiscord", "SocialMedia_Discord", 90, 3, "https://discord.gg/xQSgHsEnQ"));
            return items.toString();
        });
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

    /** People at a premier table this server hosts who have not finished drafting (the bridge warns before the client quits). */
    public int premierOthersDrafting() { return premier.othersStillDrafting(); }

    private volatile forge.mtga.social.ResultReporter results;
    private volatile forge.mtga.social.RankService rank = new forge.mtga.social.RankService((forge.mtga.social.CoordinatorSession) null, null, null);

    /** The player's rank from the coordinator (set by the server main when online play is on; otherwise the starting rank). */
    public void setRank(forge.mtga.social.RankService r) { rank = r; }

    private volatile forge.mtga.social.MasteryService mastery = new forge.mtga.social.MasteryService(null, null, null);
    private volatile MasteryTrack masteryTrack = new MasteryTrack(mastery);

    /** The mastery track from the coordinator (set by the server main when online play is on; offline: everything owned, no track). */
    public void setMastery(forge.mtga.social.MasteryService m) {
        mastery = m;
        masteryTrack = new MasteryTrack(m);
    }

    private volatile DailyQuests quests = new DailyQuests(new forge.mtga.social.QuestService(null));

    /** The daily quests from the coordinator (set by the server main when online play is on; offline: none). */
    public void setQuests(forge.mtga.social.QuestService q) { quests = new DailyQuests(q); }

    /** Local bot matches of this run: match id -> event (the quests count the format of the queue). */
    private final Map<String, String> botMatches = new java.util.concurrent.ConcurrentHashMap<>();
    /** Ranked-queue bot matches that ended with a winner: match id -> the player won, empty = a draw (a quit leaves no entry: the coordinator counts an abandoned match as a loss). */
    /** Archenemy: Sparky matches won for the first time (match id -> the stage beaten), waiting for their XP prize to be paid. */
    private final Map<String, forge.mtga.Archenemy.Stage> archenemyPrizes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.Optional<Boolean>> botRankedWins = new java.util.concurrent.ConcurrentHashMap<>();

    /** Online matches of this run (match id -> event) and, per finished match, the wait for what it earned (started by the first report request). */
    private final Map<String, String> onlineMatches = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<JsonObject>> rewardWaits = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * EventGetMatchResultReport: what the match earned, for the end screen. An online match: its verdict (the other seat may report a
     * moment later), with the rank change of a ranked match ({@code rankUpdates}, the pips) and the mastery XP of any match. A local bot
     * match: its mastery XP (the coordinator's daily cap applies). Both are fetched in the background and the client is told FoundMatch=false
     * meanwhile, which makes it ask again (it retries for 8 s; the waits give up after 6 s and the report then carries nothing).
     * Handlers run on the network thread, so nothing here may block.
     */
    private Object matchResultReport(String matchId) {
        JsonObject report = obj("CurrentModule", lastEvent == null ? "Complete" : ArchenemyEvent.is(lastEvent) ? archenemy.module() : events.module(lastEvent), "FoundMatch", true, "InventoryInfo", null,
                "questUpdates", new JsonArray(), "rankUpdates", null, "mythicRatingUpdates", null, "periodicRewardsProgress", null);
        if (matchId == null) return report;
        boolean online = onlineMatches.containsKey(matchId), bot = matchId.startsWith("forge-");
        forge.mtga.social.MasteryService ms = mastery;
        forge.mtga.social.RankService rs = rank;
        DailyQuests qs = quests;
        String event = online ? onlineMatches.get(matchId) : botMatches.get(matchId);
        if (!(online || bot) || !ms.online()) return report;
        java.util.concurrent.CompletableFuture<JsonObject> wait = rewardWaits.computeIfAbsent(matchId, id -> {
            double before = rs.percentile("constructed");
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                JsonObject earned = new JsonObject();
                if (bot) {
                    int xp = ms.botMatch(id);
                    forge.mtga.Archenemy.Stage prize = archenemyPrizes.remove(id);      // the first win of a Sparky stage: its 10,000 XP
                    if (prize != null) xp += ms.eventReward(ArchenemyEvent.prizeKey(prize));
                    earned.addProperty("xp", xp);
                    java.util.Optional<Boolean> won = botRankedWins.remove(id);
                    if (won != null && event != null && PlayQueues.isRanked(event)) {
                        JsonObject change = rs.botResult(id, event, won.orElse(null));
                        if (change != null) {
                            change.addProperty("oldPercentile", before);
                            rs.onMatch(change);
                            earned.add("rank", change);
                        }
                    }
                } else {
                    JsonObject verdict = ms.awaitVerdict(id, 5_000);
                    if (verdict != null) {
                        if (verdict.has("mastery")) earned.addProperty("xp", verdict.getAsJsonObject("mastery").get("xp").getAsInt());
                        if (verdict.has("rank")) {
                            JsonObject change = verdict.getAsJsonObject("rank");
                            change.addProperty("oldPercentile", before);
                            rs.onMatch(change);
                            earned.add("rank", change);
                        }
                    }
                }
                if (qs.enabled()) {
                    JsonArray moved = qs.afterMatch(id, questMetrics(id, event));
                    earned.add("quests", moved);
                    if (moved.toString().contains("\"inventoryUpdate\":{")) {
                        try { ms.refresh(); } catch (java.io.IOException ignored) { }      // a finished quest paid XP: the track's state moved
                    }
                }
                return earned;
            });
        });
        if (!wait.isDone()) {
            report.addProperty("FoundMatch", false);
            return report;
        }
        JsonObject earned = wait.getNow(new JsonObject());
        if (earned.has("quests")) report.add("questUpdates", earned.getAsJsonArray("quests"));
        int xp = earned.has("xp") ? earned.get("xp").getAsInt() : 0;
        if (xp > 0) report.add("InventoryInfo", MasteryTrack.xpChange(xp));
        JsonObject change = earned.has("rank") ? earned.getAsJsonObject("rank") : null;
        if (change != null) {
            report.add("rankUpdates", forge.mtga.social.RankService.progress(change));
            JsonObject mythic = forge.mtga.social.RankService.mythic(change, change.get("oldPercentile").getAsDouble());
            if (mythic != null) report.add("mythicRatingUpdates", mythic);
            System.out.println("[mtga] FD rank: match " + matchId + " " + change.get("result").getAsString() + " (" + change.get("reason").getAsString() + "): "
                    + flatPips(change.getAsJsonObject("old")) + " -> " + flatPips(change.getAsJsonObject("new")));
        } else if (online && PlayQueues.isRanked(onlineMatches.get(matchId))) {
            System.out.println("[mtga] FD rank: no rank change for match " + matchId + " (yet)");
        }
        System.out.println("[mtga] FD mastery: match " + matchId + " +" + xp + " XP (level " + ms.level() + ", " + ms.xp() % forge.mtga.profile.Mastery.LEVEL_XP + "/"
                + forge.mtga.profile.Mastery.LEVEL_XP + ")");
        return report;
    }

    /**
     * This player's quest counters of a finished match: what the engine counted for the seat (QuestMetrics: MatchPlayed, MatchWon and the
     * gameplay counters) plus the match's kind from its event: MatchPlayed_<Format> / MatchWon_<Format> for a queue, RankedPlayed, DraftPlayed.
     * Null when the engine's counters never arrived (an older host), so nothing is posted.
     */
    private static JsonObject questMetrics(String matchId, String event) {
        JsonObject m = forge.mtga.forge.QuestMetrics.take(matchId);
        if (m == null) return null;
        boolean played = m.has("MatchPlayed"), won = m.has("MatchWon");
        String format = event == null ? null : PlayQueues.formatOf(event);
        if (format != null && played) m.addProperty("MatchPlayed_" + format, 1);
        if (format != null && won) m.addProperty("MatchWon_" + format, 1);
        if (event != null && played && PlayQueues.isRanked(event)) m.addProperty("RankedPlayed", 1);
        if (event != null && played && (DraftEvents.isDraftEvent(event) || PremierDrafts.isPremier(event))) m.addProperty("DraftPlayed", 1);
        return m;
    }

    private static String flatPips(JsonObject p) { return str(p, "class") + " " + p.get("level").getAsInt() + " step " + p.get("step").getAsInt(); }

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

    /**
     * What this start can play: Bot Play always; Casual Play with online play (or a direct host/join); Ranked with the matchmaking server and
     * an account. Offline (-Dmtga.p2p=off, launcher -Offline) the blade shows Bot Play only.
     */
    private java.util.Set<PlayQueues.Mode> playableModes() {
        java.util.Set<PlayQueues.Mode> modes = java.util.EnumSet.of(PlayQueues.Mode.BOT);
        Peer p = peer;
        if (p != null) modes.add(PlayQueues.Mode.CASUAL);
        if (p != null && p.coordinator() != null && rank.online()) modes.add(PlayQueues.Mode.RANKED);
        return modes;
    }

    /** Premier drafts are tables of players formed by the matchmaking server: offline they are hidden (quick drafts stay). */
    private boolean premierShown() {
        Peer p = peer;
        return p != null && p.coordinator() != null;
    }

    private JsonArray allEvents() {
        JsonArray all = queues.eventInfos(playableModes());
        if (premierShown()) all.addAll(premier.eventInfos());
        all.addAll(events.eventInfos());
        all.addAll(archenemy.eventInfos());
        return all;
    }

    /** EventSetDeckV3: a queue event checks the deck against its format first; draft events keep their own course. */
    private Object setEventDeck(JsonObject req) {
        String name = str(req, "EventName");
        if (ArchenemyEvent.is(name)) {
            JsonObject deck = req.has("Deck") && req.get("Deck").isJsonObject() ? req.getAsJsonObject("Deck") : null;
            String problem = deck == null ? "no deck submitted" : deckProblem(ForgeFormats.BRAWL, deck);
            if (problem != null) System.out.println("[mtga] FD " + name + ": deck refused: " + problem);
            return archenemy.setDeck(req, problem);
        }
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
        return forgeFormats().problem(format, names, commandersOf(deck, new HashMap<>()), unknown);
    }

    /** A client-JSON deck as Forge card names with counts and the client printing each name has (cards Forge does not know are left out). */
    record Cards(Map<String, Integer> human, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion, java.util.List<String> commanders) {
        Cards(Map<String, Integer> human, Map<String, Integer> printing) { this(human, printing, Map.of(), null, java.util.List.of()); }
    }

    /** The deck's command zone cards (a Brawl deck's commander) as Forge card names; their printings are added to {@code printing}. */
    private java.util.List<String> commandersOf(JsonObject deck, Map<String, Integer> printing) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonArray pile = deck.has("CommandZone") && deck.get("CommandZone").isJsonArray() ? deck.getAsJsonArray("CommandZone") : null;
        if (pile == null) return out;
        for (JsonElement e : pile) {
            JsonObject c = e.getAsJsonObject();
            if (!c.has("cardId") || c.get("quantity").getAsInt() <= 0) continue;
            int grp = c.get("cardId").getAsInt();
            String name = db.name(grp);
            if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) continue;
            printing.putIfAbsent(name, grp);
            out.add(name);
        }
        return out;
    }

    /** The deck's companion (first card of its Companions pile) as a Forge card name, or null; its printing is added to {@code printing}. */
    private String companionOf(JsonObject deck, Map<String, Integer> printing) {
        JsonArray pile = deck.has("Companions") && deck.get("Companions").isJsonArray() ? deck.getAsJsonArray("Companions") : null;
        if (pile == null) return null;
        for (JsonElement e : pile) {
            JsonObject c = e.getAsJsonObject();
            if (!c.has("cardId") || c.get("quantity").getAsInt() <= 0) continue;
            int grp = c.get("cardId").getAsInt();
            String name = db.name(grp);
            if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) continue;
            printing.putIfAbsent(name, grp);
            return name;
        }
        return null;
    }

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
        Map<String, Integer> sideboard = new LinkedHashMap<>();
        JsonArray side = deck.getAsJsonArray("Sideboard");
        if (side != null) {
            for (JsonElement e : side) {
                JsonObject c = e.getAsJsonObject();
                int grp = c.get("cardId").getAsInt(), qty = c.get("quantity").getAsInt();
                String name = db.name(grp);
                if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) continue;
                sideboard.merge(name, qty, Integer::sum);
                printing.putIfAbsent(name, grp);
            }
        }
        return new Cards(human, printing, sideboard, companionOf(deck, printing), commandersOf(deck, printing));
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
        if (ArchenemyEvent.is(eventName)) {
            JsonObject c = archenemy.course();
            if (c == null || !c.has("CourseDeck") || !c.get("CourseDeck").isJsonObject() || c.getAsJsonObject("CourseDeck").getAsJsonArray("MainDeck").size() == 0) {
                return new Err(7000, "no deck for event " + eventName);
            }
            forge.mtga.Archenemy.Stage stage = archenemy.next();
            forge.mtga.AiDecks.Choice sparky = forge.mtga.Archenemy.deck(stage, n -> db.grpId(n) != 0);
            return startMatch(s, c.getAsJsonObject("CourseDeckSummary"), c.getAsJsonObject("CourseDeck"), sparky, eventName);
        }
        JsonObject course = DraftEvents.isDraftEvent(eventName) ? events.course(eventName) : null;
        if (course == null || !course.has("CourseDeck") || !course.get("CourseDeck").isJsonObject()) return new Err(7000, "no deck for event " + eventName);
        Peer p = peer;
        if (PremierDrafts.isPremier(eventName) && p != null && p.coordinator() != null) {
            Cards cards = cardsOf(course.getAsJsonObject("CourseDeck"));
            if (cards.human().isEmpty()) return new Err(7000, "no deck for event " + eventName);
            lastEvent = eventName;
            String session = null;      // signed in = the match counts for the Limited rank; an account problem only makes it an unranked match
            if (rank.online()) {
                try {
                    session = rank.session();
                } catch (java.io.IOException e) {
                    System.out.println("[mtga] FD premier: cannot sign in, the match will not count for rank (" + e.getMessage() + ")");
                }
            }
            return startOnlineMatch(s, str(course.getAsJsonObject("CourseDeckSummary"), "Name"), cards.human(), cards.printing(),
                    profile.forDeck(course.getAsJsonObject("CourseDeckSummary")), eventName, p, session, cards.companion(), cards.commanders());
        }
        Map<String, Integer> bot = events.botDeck(eventName);
        forge.mtga.AiDecks.Choice ai = bot == null ? null : new forge.mtga.AiDecks.Choice("draft bot", bot);
        return startMatch(s, course.getAsJsonObject("CourseDeckSummary"), course.getAsJsonObject("CourseDeck"), ai, eventName);
    }

    /**
     * A deck saved to the player's decks never keeps a draft-type format (the event page's copy button saves it as "Limited"
     * or its challenge alias "DirectGameLimited"): the client's deck builder crashes on a draft-type deck outside its event. It becomes
     * "Draft" (constructed type, 40 cards). True if the summary changed.
     */
    static boolean ownDeckFormat(JsonObject summary) {
        JsonElement attrs = summary.get("Attributes");
        if (attrs == null || !attrs.isJsonArray()) return false;
        boolean changed = false;
        for (JsonElement a : attrs.getAsJsonArray()) {
            JsonObject o = a.getAsJsonObject();
            if ("Format".equals(str(o, "name")) && ForgeFormats.isDraftType(str(o, "value"))) {
                o.addProperty("value", ForgeFormats.DRAFT_DECK);
                changed = true;
            }
        }
        if (summary.has("Format") && ForgeFormats.isDraftType(str(summary, "Format"))) {
            summary.addProperty("Format", ForgeFormats.DRAFT_DECK);
            changed = true;
        }
        return changed;
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
        // the sideboard: only a best-of-three plays with it
        Map<String, Integer> sideboard = new LinkedHashMap<>();
        JsonArray side = deck.getAsJsonArray("Sideboard");
        if (side != null) {
            for (JsonElement e : side) {
                JsonObject c = e.getAsJsonObject();
                int grp = c.get("cardId").getAsInt(), qty = c.get("quantity").getAsInt();
                String name = db.name(grp);
                if (name == null || StaticData.instance().getCommonCards().getUniqueByName(name) == null) continue;
                sideboard.merge(name, qty, Integer::sum);
                printing.putIfAbsent(name, grp);
            }
        }
        String companion = companionOf(deck, printing);
        // only a Brawl game plays the command zone (a Commander deck keeps playing as 99 cards + the rest)
        java.util.List<String> commanders = ArchenemyEvent.is(eventId) || ForgeFormats.BRAWL.equals(PlayQueues.isQueueEvent(eventId) ? PlayQueues.formatOf(eventId) : deckFormat(summary))
                ? commandersOf(deck, printing) : java.util.List.of();
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
            return startPeerMatch(s, deckName, human, printing, me, eventId, companion, commanders);
        }
        if (mode == PlayQueues.Mode.RANKED) {
            Peer p = peer;
            if (p == null || p.coordinator() == null || !rank.online()) {
                return new Err(7000, "Ranked needs online play with an account, which is switched off in this start (-Dmtga.p2p=off or a direct host/join)");
            }
            String session;
            try {
                session = rank.session();
            } catch (java.io.IOException e) {
                System.out.println("[mtga] FD ranked: cannot sign in (" + e.getMessage() + ")");
                return new Err(7000, "Can't sign in to the matchmaking server, so Ranked is not available right now. Check your internet connection and try again.");
            }
            return startOnlineMatch(s, deckName, human, printing, me, eventId, p, session, companion, java.util.List.of());
        }
        if (ArchenemyEvent.is(eventId)) {
            String problem = deckProblem(ForgeFormats.BRAWL, deck);      // the server's own check, as for a queue
            if (problem != null) return new Err(7000, "Archenemy: Sparky: " + problem);
            return launchBotMatch(s, deckName, human, printing, sideboard, companion, commanders, aiOverride, profile.forBoss(aiOverride.name()), me, eventId);
        }
        forge.mtga.AiDecks.Choice ai = aiOverride != null ? aiOverride : aiDeck(mode == PlayQueues.Mode.BOT ? queueFormat : deckFormat(summary));
        return launchBotMatch(s, deckName, human, printing, sideboard, companion, commanders, ai, profile.forAi(), me, eventId);
    }

    /** The local match against the Forge AI: set up the engine seat, remember it as a bot match and push MatchCreated. */
    private Object launchBotMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing, Map<String, Integer> sideboard,
                                  String companion, java.util.List<String> commanders, forge.mtga.AiDecks.Choice ai, PublicProfile opponent, PublicProfile me, String eventId) {
        String matchId = "forge-" + System.currentTimeMillis() + "-" + (++matchCounter);
        String aiName = opponent.name();
        boolean event = DraftEvents.isDraftEvent(eventId), sparky = ArchenemyEvent.is(eventId);
        lastEvent = event || sparky ? eventId : null;
        int gamesToWin = PlayQueues.gamesToWin(eventId);
        boolean ranked = PlayQueues.isRanked(eventId);
        forge.mtga.Archenemy.Stage stage = sparky ? archenemy.next() : null;
        java.util.function.Consumer<Boolean> onResult = sparky ? won -> {
            if (won != null && archenemy.recordResult(stage, won)) archenemyPrizes.put(matchId, stage);
        } : event ? won -> { if (won != null) events.recordResult(eventId, won); } : ranked ? won -> botRankedWins.put(matchId, won == null ? java.util.Optional.empty() : java.util.Optional.of(won)) : null;
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, ai.cards(), printing, aiName, null,
                onResult, gamesToWin > 1 ? sideboard : Map.of(), gamesToWin, companion, ai.companion(), commanders, ai.commanders(), ai.schemes(), ai.life()));
        if (ranked) rank.botStartAsync(matchId, eventId);
        int total = human.values().stream().mapToInt(Integer::intValue).sum();
        botMatches.put(matchId, eventId);
        System.out.println("[mtga] FD bot match " + matchId + " (" + eventId + "): '" + deckName + "' (" + total + " cards) vs AI deck '" + ai.name() + "'");
        Capture.log("FD bot match " + matchId + " deck '" + deckName + "' vs '" + ai.name() + "'");

        pushMatchCreated(s, matchId, me, opponent, eventId, commanders, ai.commanders());
        return GSON.toJson(matchId);
    }

    /**
     * Player vs player instead of a bot match (-Dmtga.p2p). Host: this client's seat is built now and the client waits in its
     * queue screen until a guest joins. Guest: connects to the host, then plays through the host's engine.
     * Either way the client's own Match Door connection (127.0.0.1) is what it plays on. The two servers swap the PUBLIC part
     * of the players' profiles in the peer handshake, so each client draws the other's avatar, sleeve, pet, title and emotes.
     */
    private Object startPeerMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId, String companion,
                                  java.util.List<String> commanders) {
        Peer p = peer;
        if (p.coordinator() != null) return startOnlineMatch(s, deckName, human, printing, me, eventId, p, null, companion, commanders);
        if (p.host() != null) {
            String matchId = "p2p-" + System.currentTimeMillis() + "-" + (++matchCounter);
            forge.mtga.forge.ForgeMatch room = new forge.mtga.forge.ForgeMatch(db, matchId);
            forge.mtga.forge.ForgeBackend mine = new forge.mtga.forge.ForgeBackend(db, room, 0, new forge.mtga.forge.ForgeMatch.Seat(me.name(), human, printing, Map.of(), companion, commanders));
            forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, Map.of(), printing, "", mine));
            System.out.println("[mtga] FD P2P host: '" + deckName + "' waits for a guest (match " + matchId + ")");
            p.host().offer(new forge.mtga.peer.PeerHost.Offer(matchId, room, me, eventId, guest -> pushMatchCreated(s, matchId, me, profile.sanitize(guest), eventId, commanders, room.commanders(1))));
            return GSON.toJson(matchId);
        }
        forge.mtga.peer.PeerGuest.Joined j = forge.mtga.peer.PeerGuest.join(p.joinHost(), p.joinPort(), p.key(), p.fingerprint(), eventId, me, human, printing, Map.of(), companion, commanders);
        if (j.error() != null) return new Err(7000, "P2P: " + j.error());
        forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(j.matchId(), human, Map.of(), printing, "", j.backend()));
        System.out.println("[mtga] FD P2P guest: '" + deckName + "' vs " + j.host().name() + " (match " + j.matchId() + ")");
        pushMatchCreated(s, j.matchId(), me, profile.sanitize(j.host()), eventId, commanders, j.hostCommanders());
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
    private Object startOnlineMatch(FdSession s, String deckName, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId, Peer p, String session, String companion,
                                    java.util.List<String> commanders) {
        leaveOnlineQueue();
        forge.mtga.peer.Coordinator.Ticket ticket = forge.mtga.peer.Coordinator.queue(p.coordinator(), eventId, p.fingerprint(),
                forge.mtga.peer.PeerGuest.VERSION, me.playerId(), me.name(), p.friendCode(), session);
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
        scheduleRankedBot(s, ticket, deckName, human, printing, companion, me, eventId);
        ticket.result().whenComplete((paired, err) -> {
            if (err != null) {
                if (!ticket.result().isCancelled()) System.out.println("[mtga] FD online: no match (" + err.getMessage() + ")");
                return;
            }
            Thread t = new Thread(() -> playOnline(s, paired, human, printing, Map.of(), 1, -1, me, eventId, p, companion, commanders), "online-match");
            t.setDaemon(true);
            t.start();
        });
        return GSON.toJson("online-" + System.currentTimeMillis() + "-" + (++matchCounter));
    }

    /** Until the player base is there: Bronze..Gold ranked queues that find nobody for 25-45 s play a human-looking bot of the player's own rank (no rank change). */
    static final int RANKED_BOT_MIN_SECS = 25, RANKED_BOT_MAX_SECS = 45;

    static boolean rankedBotClass(String rankClass) {
        return "Bronze".equals(rankClass) || "Silver".equals(rankClass) || "Gold".equals(rankClass);
    }

    private void scheduleRankedBot(FdSession s, forge.mtga.peer.Coordinator.Ticket ticket, String deckName, Map<String, Integer> human, Map<String, Integer> printing,
                                   String companion, PublicProfile me, String eventId) {
        if (!PlayQueues.isRanked(eventId) || !rankedBotClass(me.rankClass()) || Boolean.getBoolean("mtga.ranked.nobots")) return;
        int secs = RANKED_BOT_MIN_SECS + java.util.concurrent.ThreadLocalRandom.current().nextInt(RANKED_BOT_MAX_SECS - RANKED_BOT_MIN_SECS + 1);
        LATER.schedule(() -> {
            if (onlineTicket != ticket || !ticket.result().cancel(false)) return;   // left the queue, or a human was paired first
            ticket.cancel();
            onlineTicket = null;
            try {
                forge.mtga.AiDecks.Choice ai = aiDeck(PlayQueues.formatOf(eventId));
                System.out.println("[mtga] FD ranked: nobody in " + eventId + " after " + secs + " s, bot of rank " + me.rankClass() + " " + me.rankTier());
                launchBotMatch(s, deckName, human, printing, Map.of(), companion, java.util.List.of(), ai, profile.forAi(me.rankClass(), me.rankTier()), me, eventId);
            } catch (RuntimeException e) {
                System.out.println("[mtga] FD ranked: bot fallback failed: " + e);
            }
        }, secs, java.util.concurrent.TimeUnit.SECONDS);
    }

    void playOnline(FdSession s, forge.mtga.peer.Coordinator.Paired paired, Map<String, Integer> human, Map<String, Integer> printing, PublicProfile me, String eventId, Peer p) {
        playOnline(s, paired, human, printing, Map.of(), 1, -1, me, eventId, p, null);
    }

    void playOnline(FdSession s, forge.mtga.peer.Coordinator.Paired paired, Map<String, Integer> human, Map<String, Integer> printing,
                    Map<String, Integer> sideboard, int gamesToWin, int firstSeat, PublicProfile me, String eventId, Peer p) {
        playOnline(s, paired, human, printing, sideboard, gamesToWin, firstSeat, me, eventId, p, null);
    }

    /**
     * {@code gamesToWin} 2 = best of three: both players sideboard between games (the host's engine runs them; a guest only sends its sideboard).
     * {@code firstSeat} (host's engine only): 0 = the host (challenge owner) plays first in game 1, 1 = the guest does, -1 = coin toss.
     */
    void playOnline(FdSession s, forge.mtga.peer.Coordinator.Paired paired, Map<String, Integer> human, Map<String, Integer> printing,
                    Map<String, Integer> sideboard, int gamesToWin, int firstSeat, PublicProfile me, String eventId, Peer p, String companion) {
        playOnline(s, paired, human, printing, sideboard, gamesToWin, firstSeat, me, eventId, p, companion, java.util.List.of());
    }

    void playOnline(FdSession s, forge.mtga.peer.Coordinator.Paired paired, Map<String, Integer> human, Map<String, Integer> printing,
                    Map<String, Integer> sideboard, int gamesToWin, int firstSeat, PublicProfile me, String eventId, Peer p, String companion, java.util.List<String> commanders) {
        String matchId = paired.match();
        System.out.println("[mtga] FD online: paired with " + paired.opponent() + " as " + paired.role() + ", match " + matchId);
        onlineMatches.put(matchId, eventId);
        forge.mtga.peer.PeerLink link = null;
        try {
            if (paired.host()) {
                forge.mtga.forge.ForgeMatch room = new forge.mtga.forge.ForgeMatch(db, matchId);
                forge.mtga.forge.ForgeBackend mine = new forge.mtga.forge.ForgeBackend(db, room, 0, new forge.mtga.forge.ForgeMatch.Seat(me.name(), human, printing, sideboard, companion, commanders));
                room.setGamesToWin(gamesToWin);
                room.setFirstSeat(firstSeat);
                forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(matchId, human, Map.of(), printing, "", mine));
                mine.setOutcomeListener(outcomeReport(paired, eventId));
                p.host().offer(new forge.mtga.peer.PeerHost.Offer(matchId, room, me, eventId, guest -> pushMatchCreated(s, matchId, me, profile.sanitize(guest), eventId, commanders, room.commanders(1))));
                link = forge.mtga.peer.Coordinator.openRoom(paired);
                p.host().serveOnline(link);              // returns when the guest has left
                return;
            }
            link = forge.mtga.peer.Coordinator.openRoom(paired);
            forge.mtga.peer.PeerGuest.Joined j = forge.mtga.peer.PeerGuest.joinOnline(link, p.fingerprint(), eventId, me, human, printing, sideboard, companion, commanders);
            if (j.error() != null) {
                System.out.println("[mtga] FD online: join failed: " + j.error());
                return;
            }
            j.backend().setOutcomeListener(outcomeReport(paired, eventId));
            j.backend().setMetricsListener(m -> forge.mtga.forge.QuestMetrics.publish(j.matchId(), m));
            onlineMatches.put(j.matchId(), eventId);
            forge.mtga.MatchSetup.publish(new forge.mtga.MatchSetup(j.matchId(), human, Map.of(), printing, "", j.backend()));
            pushMatchCreated(s, j.matchId(), me, profile.sanitize(j.host()), eventId, commanders, j.hostCommanders());
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
        pushMatchCreated(s, matchId, me, opponent, eventId, java.util.List.of(), java.util.List.of());
    }

    /**
     * {@code mine}/{@code theirs}: each player's commanders (Brawl). The client's versus screen draws them and fails on a Brawl game whose
     * players have none (VSScreen.SetupCommander), so a game that leaves them out never starts.
     */
    private void pushMatchCreated(FdSession s, String matchId, PublicProfile me, PublicProfile opponent, String eventId,
                                  java.util.List<String> mine, java.util.List<String> theirs) {
        JsonArray players = new JsonArray();
        players.add(playerInfo(me, 1, 1, mine));
        players.add(playerInfo(opponent, 2, 2, theirs));
        JsonObject info = obj("MatchEndpointHost", matchHost, "MatchEndpointPort", matchPort, "MatchId", matchId,
                "McFabricId", "fabric:/forge/" + matchId, "EventId", eventId, "Battlefield", "", "MatchType", "Queue",
                "YourSeat", 1, "ClientMetadata", obj(), "PlayerInfos", players);
        String push = obj("Type", "MatchCreated", "MatchInfoV4", info).toString();
        LATER.schedule(() -> {
            Capture.log("FD push MatchCreated " + Capture.flat(push, 600));
            s.push(push);
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private JsonObject playerInfo(PublicProfile p, int seat, int team, java.util.List<String> commanders) {
        JsonObject o = obj("ScreenName", p.name(), "RankingClass", p.rankClass(), "RankingTier", p.rankTier(), "SeatId", seat, "TeamId", team,
                "IsWotc", isDev(p), "CosmeticsSelection", profile.selectionJson(p));
        if (!commanders.isEmpty()) {
            JsonArray list = new JsonArray();
            for (String c : commanders) if (db.grpId(c) != 0) list.add(obj("GrpId", db.grpId(c), "StyleCode", ""));
            o.add("Commanders", list);
        }
        return o;
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
        if ((spec == null || spec.isBlank()) && ForgeFormats.BRAWL.equals(format)) return forge.mtga.AiDecks.brawl(n -> db.grpId(n) != 0);
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

    private JsonObject graphState(String graphId) {
        if (masteryTrack.enabled() && MasteryTrack.GRAPH_ID.equals(graphId)) return masteryTrack.state();
        for (Graph g : GRAPHS) if (g.id().equals(graphId)) return g.state();
        return obj("NodeStates", new JsonObject(), "MilestoneStates", new JsonObject());
    }

    // ---- starter decks ------------------------------------------------------------------------------------------

    /** The built-in decks this player was given (one name per line): never given twice, so a deleted one stays deleted. */
    private static final String SEED_LEDGER = ".seeded-decks";
    /** The previous marker (one shot per deck set); a profile that has it is moved to the ledger once. */
    private static final String SEED_MARKER_V4 = ".seeded-defaults-v4";
    /** Formats every client could always seed (before the Legacy/Vintage/Pauper cards were added to the client's card database). */
    private static final java.util.Set<String> ALWAYS_SEEDABLE = java.util.Set.of("Standard", "Pioneer", "Modern");

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
     * Every start: the player gets each built-in deck of every queue format (DefaultDecks) once, as "<Name> (<Format>)". The ledger
     * data/.seeded-decks lists the ones given (seeded, or found already there): a deck the player deleted is not given again, and one that
     * could not be seeded yet (a card missing from this client's card database: an older patched folder) is tried again at the next start.
     * A profile with the earlier one-shot marker (.seeded-defaults-v4) is moved to the ledger: Standard/Pioneer/Modern decks count as given,
     * Legacy/Vintage/Pauper ones only when present (those were skipped on clients without their cards). The earlier starter decks
     * ("Forge: <precon>", Timeless) are removed if the player never changed them. Delete the ledger to seed everything again.
     */
    private void seedStarterDecks(File dataDir) {
        File ledgerFile = new File(dataDir, SEED_LEDGER);
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
        java.util.Set<String> have = new java.util.HashSet<>();
        for (JsonObject d : decks.all()) have.add(str(d.getAsJsonObject("Summary"), "Name"));
        java.util.Set<String> given = new java.util.LinkedHashSet<>();
        try {
            if (ledgerFile.isFile()) {
                for (String line : Files.readAllLines(ledgerFile.toPath(), StandardCharsets.UTF_8)) if (!line.isBlank()) given.add(line.strip());
            } else if (new File(dataDir, SEED_MARKER_V4).exists()) {
                for (forge.mtga.DefaultDecks.Entry d : forge.mtga.DefaultDecks.all()) {
                    String name = d.name() + " (" + d.format() + ")";
                    if (ALWAYS_SEEDABLE.contains(d.format()) || have.contains(name)) given.add(name);
                }
            }
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot read " + ledgerFile + ": " + e);
            return;                                  // never risk giving deleted decks back
        }
        int made = 0, before = given.size();
        for (forge.mtga.DefaultDecks.Entry d : forge.mtga.DefaultDecks.all()) {
            String name = d.name() + " (" + d.format() + ")";
            if (given.contains(name)) continue;
            try {
                if (have.contains(name)) given.add(name);
                else if (playable(d) && saveDeck(name, d.format(), d.main(), d.side(), d.companion())) { given.add(name); made++; }
            } catch (Exception e) {
                System.out.println("[mtga] FD: cannot seed " + d.name() + ": " + e);
            }
        }
        moveCompanions();
        if (given.size() != before || !ledgerFile.isFile()) {
            try {
                ledgerFile.getParentFile().mkdirs();
                Files.writeString(ledgerFile.toPath(), String.join(System.lineSeparator(), given) + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.out.println("[mtga] FD: cannot write " + ledgerFile + ": " + e);
            }
        }
        System.out.println("[mtga] FD built-in decks: " + made + " seeded now, " + given.size() + " of " + forge.mtga.DefaultDecks.all().size() + " given");
    }

    /**
     * Decks seeded before companions were a pile of their own hold the companion in the sideboard: move it to Companions (only a
     * built-in deck's own companion, only while its Companions pile is empty).
     */
    private void moveCompanions() {
        for (forge.mtga.DefaultDecks.Entry d : forge.mtga.DefaultDecks.all()) {
            if (d.companion() == null || db.grpId(d.companion()) == 0) continue;
            String name = d.name() + " (" + d.format() + ")";
            int grp = db.grpId(d.companion());
            for (JsonObject saved : decks.all()) {
                JsonObject summary = saved.getAsJsonObject("Summary"), deck = saved.getAsJsonObject("Deck");
                if (!name.equals(str(summary, "Name")) || !deck.has("Sideboard") || (deck.has("Companions") && deck.getAsJsonArray("Companions").size() > 0)) continue;
                JsonArray side = deck.getAsJsonArray("Sideboard"), rest = new JsonArray();
                JsonObject found = null;
                for (JsonElement e : side) {
                    JsonObject c = e.getAsJsonObject();
                    if (found == null && c.has("cardId") && db.primaryPrinting(c.get("cardId").getAsInt()) == db.primaryPrinting(grp)) found = c; else rest.add(c);
                }
                if (found == null) continue;
                JsonArray companions = new JsonArray();
                companions.add(obj("cardId", found.get("cardId").getAsInt(), "quantity", 1));
                deck.add("Sideboard", rest);
                deck.add("Companions", companions);
                summary.addProperty("IsCompanionValid", true);
                try {
                    decks.put(str(summary, "DeckId"), summary, deck);
                    System.out.println("[mtga] FD " + name + ": " + d.companion() + " is now its companion");
                } catch (java.io.IOException ex) {
                    System.out.println("[mtga] FD: cannot update " + name + ": " + ex);
                }
            }
        }
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
    private boolean saveDeck(String name, String format, Map<String, Integer> cards, Map<String, Integer> side, String companion) throws java.io.IOException {
        int[] totals = new int[2];   // cards, tile
        JsonArray main = cardList(cards, name, totals);
        JsonArray sideboard = cardList(side, name, new int[2]);
        JsonArray companions = companion == null ? new JsonArray() : cardList(Map.of(companion, 1), name, new int[2]);
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
                "DeckTileId", tile, "DeckArtId", db.artId(tile), "IsCompanionValid", companions.size() > 0,
                "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
        JsonObject deck = obj("MainDeck", main, "Sideboard", sideboard, "CommandZone", new JsonArray(),
                "Companions", companions, "CardSkins", new JsonArray());
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
        // fresh (it only calls DeckGetDeckSummariesV3 after something marks it dirty). The cache version must change with
        // the decks: the client re-runs StartHook after a match and keeps its cached list when the version is unchanged,
        // which hid every deck saved or imported since the first StartHook until the client restarted.
        int version = decks.version();
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
        b.setDeckSummariesCacheVersion(version).setDecksCacheVersion(version);
        // the draft events' prize for winning out (an Event-type token: the rewards panel shows it with the EventToken model)
        b.setTokenDefinitionsCacheVersion(1).addTokenDefinitions(TokenDefinition.newBuilder().setTokenId(DraftEvents.PRIZE_TOKEN)
                .setExpirationDateInternal(Timestamp.newBuilder().setSeconds(4070908800L))   // 2099: the client hides expired tokens
                .setTokenTypeInternal(1).setPrefabName("EventToken")
                .setHeaderLocKey("Forge/Token/EventChampion").setDescriptionLocKey("Forge/Token/EventChampion_Desc"));
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

    // ---- home carousel ------------------------------------------------------------------------------------------

    private static JsonObject carouselItem(String name, String art, int priority, int actionType, String argument) {
        JsonArray actions = new JsonArray();
        actions.add(obj("Type", actionType, "Arguments", argument));
        return obj("Name", name, "AssetTreeItem", art, "Priority", priority,
                "TitleKey", "Forge/Carousel/" + name + "_Title", "DescriptionKey", "Forge/Carousel/" + name + "_Desc",
                "Actions", actions, "HoverActions", new JsonArray());
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
