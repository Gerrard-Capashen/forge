package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.BoolValue;
import com.wizards.mtga.fd.Challenge;
import com.wizards.mtga.fd.ChallengeCloseReq;
import com.wizards.mtga.fd.ChallengeCreateReq;
import com.wizards.mtga.fd.ChallengeExitReq;
import com.wizards.mtga.fd.ChallengeIssueReq;
import com.wizards.mtga.fd.ChallengeJoinReq;
import com.wizards.mtga.fd.ChallengeKickReq;
import com.wizards.mtga.fd.ChallengeMessageResp;
import com.wizards.mtga.fd.ChallengePlayer;
import com.wizards.mtga.fd.ChallengeReadyReq;
import com.wizards.mtga.fd.ChallengeReconnectAllResp;
import com.wizards.mtga.fd.ChallengeSetPlayerSettingsReq;
import com.wizards.mtga.fd.ChallengeSetSettingsReq;
import com.wizards.mtga.fd.ChallengeStartLaunchCountdownReq;
import com.wizards.mtga.fd.ChallengeStatusResp;
import com.wizards.mtga.fd.ChallengeUnreadyReq;
import com.wizards.mtga.fd.PreferredCosmeticsOnDeck;
import forge.mtga.peer.Coordinator;
import forge.mtga.profile.PublicProfile;
import forge.mtga.social.Accounts;
import forge.mtga.social.SocialClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's direct challenges (the Challenge screen, friends only) on the Front Door: the Challenge* commands are forwarded to the
 * coordinator's Challenge object (coordinator/src/challenge.ts), which both players share, and its pushes become the client's
 * {@code ChallengeNotification}s. When both players have "issued" (after the launch countdown) the coordinator hands out a relay room and
 * the match runs like any online match: the challenger's engine hosts.
 *
 * <p>Format: the challenge screen has fixed slots; ours are re-skinned: DirectGame = Standard, DirectGameBrawl = Pioneer,
 * DirectGameAlchemy = Modern, DirectGameLimited = Legacy, DirectGameTournamentMode = Vintage, DirectGameTournamentLimited = Pauper (the bridge's
 * ChallengeSlots rebuilds the client's spinners to match; docs/online-identity-friends.md, section 8). Best of 3 and "who plays first"
 * are not supported yet (one game, the engine decides).
 */
final class ChallengeService {
    static final List<String> COMMANDS = List.of("ChallengeCreate", "ChallengeJoin", "ChallengeExit", "ChallengeClose", "ChallengeKick", "ChallengeReady",
            "ChallengeUnready", "ChallengeSetSettings", "ChallengeSetPlayerSettings", "ChallengeStartLaunchCountdown", "ChallengeIssue",
            "ChallengeInvite", "ChallengeSendMessage", "ChallengeReconnectAll");

    /** Challenge slot (client match type) -> format whose rules the decks are checked against. */
    static final Map<String, String> FORMAT_OF = Map.of("DirectGame", "Standard", "DirectGameBrawl", "Pioneer", "DirectGameAlchemy", "Modern",
            "DirectGameLimited", "Legacy", "DirectGameTournamentMode", "Vintage", "DirectGameTournamentLimited", "Pauper");

    /** The event id every challenge match is created with (the client treats it as its private game). */
    static final String EVENT = "DirectGame";

    /** What a player's own server needs to start the match once the coordinator has paired both sides. */
    private record Pending(Map<String, Integer> human, Map<String, Integer> printing, Map<String, Integer> sideboard, int gamesToWin, PublicProfile me) { }

    private final FrontDoor fd;
    private final SocialClient social;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    ChallengeService(FrontDoor fd, SocialClient social) {
        this.fd = fd;
        this.social = social;
        social.onChallengeEvent(this::onEvent);
    }

    // ---------------------------------------------------------------------------------------------------- commands

    Object handle(String cmd, FdSession s, FrontDoor.Req r) throws Exception {
        try {
            switch (cmd) {
                case "ChallengeCreate": return create(r.any().unpack(ChallengeCreateReq.class));
                case "ChallengeJoin": return join(r.any().unpack(ChallengeJoinReq.class).getChallengeId());
                case "ChallengeExit": return post(r.any().unpack(ChallengeExitReq.class).getChallengeId(), "exit", new JsonObject());
                case "ChallengeClose": return post(r.any().unpack(ChallengeCloseReq.class).getChallengeId(), "close", new JsonObject());
                case "ChallengeKick": {
                    ChallengeKickReq k = r.any().unpack(ChallengeKickReq.class);
                    JsonObject b = new JsonObject();
                    b.addProperty("playerId", k.getPlayerId());
                    return post(k.getChallengeId(), "kick", b);
                }
                case "ChallengeReady": return ready(r.any().unpack(ChallengeReadyReq.class));
                case "ChallengeUnready": return post(r.any().unpack(ChallengeUnreadyReq.class).getChallengeId(), "unready", new JsonObject());
                case "ChallengeSetSettings": return settings(r.any().unpack(ChallengeSetSettingsReq.class));
                case "ChallengeSetPlayerSettings": {
                    ChallengeSetPlayerSettingsReq p = r.any().unpack(ChallengeSetPlayerSettingsReq.class);
                    JsonObject b = new JsonObject();
                    b.addProperty("teamId", p.getTeamId());
                    return post(p.getChallengeId(), "player", b);
                }
                case "ChallengeStartLaunchCountdown": return post(r.any().unpack(ChallengeStartLaunchCountdownReq.class).getChallengeId(), "launch", new JsonObject());
                case "ChallengeIssue": return issue(r.any().unpack(ChallengeIssueReq.class));
                case "ChallengeInvite": return BoolValue.of(true);                       // invites travel as friend messages (SocialClient.sendGame)
                case "ChallengeSendMessage": return ChallengeMessageResp.getDefaultInstance();
                case "ChallengeReconnectAll": return reconnectAll();
                default: return new FrontDoor.Err(FrontDoor.ERR_NOT_IMPLEMENTED, cmd);
            }
        } catch (Accounts.ApiException e) {
            System.out.println("[mtga] FD " + cmd + " refused: " + e.reason);
            return new FrontDoor.Err(e.status == 404 ? 103 : 7000, e.reason);        // 103: the challenge is gone (the client closes it)
        } catch (IOException e) {
            return new FrontDoor.Err(7000, "matchmaking server unreachable");
        }
    }

    private Object create(ChallengeCreateReq req) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("title", req.getChallengeName());
        b.addProperty("name", "DirectGame");
        b.add("cosmetics", cosmetics(fd.profile().self()));
        JsonObject res = social.api("POST", "/challenges", b);
        System.out.println("[mtga] FD challenge created: " + res.getAsJsonObject("challenge").get("id").getAsString());
        return status(res);
    }

    private Object join(String id) throws IOException {
        JsonObject b = new JsonObject();
        b.add("cosmetics", cosmetics(fd.profile().self()));
        return status(social.api("POST", "/challenges/" + id + "/join", b));
    }

    private Object post(String id, String op, JsonObject body) throws IOException {
        return status(social.api("POST", "/challenges/" + id + "/" + op, body));
    }

    private Object settings(ChallengeSetSettingsReq req) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("name", req.getChallengeName());
        b.addProperty("playFirst", new String[] {"Random", "Challenger", "Opponent"}[Math.max(0, Math.min(2, req.getPlayFirstInternal()))]);
        b.addProperty("winCondition", req.getWinConditionInternal() >= 2 ? "BestOf3" : "SingleElimination");
        b.addProperty("limit", 2);
        return post(req.getChallengeId(), "settings", b);
    }

    /** The deck the player picked: checked against the challenge's format, then marked ready with the deck's name, art and cosmetics. */
    private Object ready(ChallengeReadyReq req) throws IOException {
        JsonObject stored = fd.decks().get(req.getDeckId());
        if (stored == null) return new FrontDoor.Err(7000, "deck not found");
        String name = social.api("GET", "/challenges/" + req.getChallengeId(), null).getAsJsonObject("challenge").get("name").getAsString();
        String problem = fd.deckProblem(FORMAT_OF.getOrDefault(name, "Standard"), stored.getAsJsonObject("Deck"));
        if (problem != null) return new FrontDoor.Err(7000, problem);
        JsonObject summary = stored.getAsJsonObject("Summary");
        JsonObject b = new JsonObject();
        b.addProperty("deckId", req.getDeckId());
        b.addProperty("deckName", FrontDoor.str(summary, "Name"));
        b.addProperty("artId", intOf(summary, "DeckArtId"));
        b.addProperty("tileId", intOf(summary, "DeckTileId"));
        b.add("cosmetics", cosmetics(fd.profile().forDeck(summary)));
        return post(req.getChallengeId(), "ready", b);
    }

    /**
     * After the countdown each client issues: remember what this server needs for the match, tell the coordinator; when both have
     * issued, {@link #onEvent} gets the "match" push and starts the game.
     */
    private Object issue(ChallengeIssueReq req) throws IOException {
        JsonObject stored = fd.decks().get(req.getDeckId());
        if (stored == null) return new FrontDoor.Err(7000, "deck not found");
        JsonObject challenge = social.api("GET", "/challenges/" + req.getChallengeId(), null).getAsJsonObject("challenge");
        String name = challenge.get("name").getAsString();
        JsonObject deck = stored.getAsJsonObject("Deck");
        String problem = fd.deckProblem(FORMAT_OF.getOrDefault(name, "Standard"), deck);
        if (problem != null) return new FrontDoor.Err(7000, problem);
        FrontDoor.Cards cards = fd.cardsOf(deck);
        int gamesToWin = winCondition(s(challenge, "winCondition"));        // 2 = best of three
        pending.put(req.getChallengeId(), new Pending(cards.human(), cards.printing(), gamesToWin > 1 ? cards.sideboard() : Map.of(), gamesToWin,
                fd.profile().forDeck(stored.getAsJsonObject("Summary"))));
        try {
            return post(req.getChallengeId(), "issue", new JsonObject());
        } catch (IOException e) {
            pending.remove(req.getChallengeId());
            throw e;
        }
    }

    private Object reconnectAll() throws IOException {
        ChallengeReconnectAllResp.Builder b = ChallengeReconnectAllResp.newBuilder();
        for (JsonElement c : social.api("GET", "/challenges", null).getAsJsonArray("challenges")) b.addChallenge(proto(c.getAsJsonObject()));
        return b.build();
    }

    // ---------------------------------------------------------------------------------------------------- pushes

    /** A coordinator push about a challenge: tell the client, or start the match. */
    private void onEvent(JsonObject m) {
        String kind = m.get("kind").getAsString();
        FdSession s = fd.session();
        switch (kind) {
            case "status" -> notify(s, "ChallengeStatus", m.getAsJsonObject("challenge").get("id").getAsString(), json(m.getAsJsonObject("challenge")), 0);
            case "countdown" -> notify(s, "ChallengeStartLaunchCountdown", m.get("id").getAsString(), null, m.get("seconds").getAsInt());
            case "kick" -> notify(s, "ChallengeKick", m.get("id").getAsString(), null, 0);
            case "close" -> {
                pending.remove(m.get("id").getAsString());
                notify(s, "ChallengeClose", m.get("id").getAsString(), null, 0);
            }
            case "match" -> startMatch(s, m);
            default -> { }
        }
    }

    private void notify(FdSession s, String type, String id, JsonObject challenge, int countdown) {
        if (s == null || !s.isOpen()) return;
        JsonObject n = new JsonObject();
        n.addProperty("ChallengeNotificationType", type);
        n.addProperty("ChallengeId", id);
        if (challenge != null) n.add("Challenge", challenge);
        if (countdown > 0) n.addProperty("CountdownSeconds", countdown);
        JsonObject push = new JsonObject();
        push.addProperty("Type", "ChallengeNotification");
        push.add("ChallengeNotification", n);
        s.push(push.toString());
    }

    private void startMatch(FdSession s, JsonObject m) {
        String id = m.get("id").getAsString();
        Pending p = pending.remove(id);
        if (p == null || s == null) {
            System.out.println("[mtga] FD challenge " + id + ": match arrived without a deck issued here, ignored");
            return;
        }
        Coordinator.Paired paired = new Coordinator.Paired(fd.peer().coordinator(), m.get("role").getAsString(), m.get("match").getAsString(), m.get("token").getAsString(),
                m.getAsJsonObject("opponent").get("fullName").getAsString());
        Thread t = new Thread(() -> fd.playOnline(s, paired, p.human(), p.printing(), p.sideboard(), p.gamesToWin(), p.me(), EVENT, fd.peer()), "challenge-match");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------------------------------------------- conversion

    private static int intOf(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsInt() : 0; }

    private static JsonObject cosmetics(PublicProfile p) {
        JsonObject o = new JsonObject();
        o.addProperty("avatar", p.avatar());
        o.addProperty("sleeve", p.sleeve());
        o.addProperty("pet", p.pet());
        o.addProperty("title", p.title());
        JsonArray emotes = new JsonArray();
        p.emotes().forEach(emotes::add);
        o.add("emotes", emotes);
        return o;
    }

    private static String s(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    private static int playFirst(String v) { return "Challenger".equals(v) ? 1 : "Opponent".equals(v) ? 2 : 0; }

    private static int winCondition(String v) { return "BestOf3".equals(v) ? 2 : 1; }

    /** The coordinator's challenge as the client's protobuf {@code Challenge}. */
    private static Challenge proto(JsonObject c) {
        Challenge.Builder b = Challenge.newBuilder().setChallengeId(s(c, "id")).setChallengeTitle(s(c, "title")).setChallengeName(s(c, "name"))
                .setPlayFirstInternal(playFirst(s(c, "playFirst"))).setWinConditionInternal(winCondition(s(c, "winCondition")))
                .setOwnerPlayerId(s(c, "owner")).setChallengeLimit(c.get("limit").getAsInt());
        for (JsonElement e : c.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            JsonObject cos = p.getAsJsonObject("cosmetics");
            PreferredCosmeticsOnDeck.Builder cb = PreferredCosmeticsOnDeck.newBuilder().setAvatar(s(cos, "avatar")).setSleeve(s(cos, "sleeve")).setPet(s(cos, "pet")).setTitle(s(cos, "title"));
            for (JsonElement em : cos.getAsJsonArray("emotes")) cb.addEmotes(em.getAsString());
            b.addPlayers(ChallengePlayer.newBuilder().setPlayerId(s(p, "playerId")).setDisplayName(s(p, "fullName")).setIsWotc(p.has("dev") && p.get("dev").getAsBoolean()).setReady(p.get("ready").getAsBoolean())
                    .setCosmetics(cb).setDeckArtId(p.get("artId").getAsInt()).setDeckTileId(p.get("tileId").getAsInt()).setTeamId(p.get("teamId").getAsInt()));
        }
        return b.build();
    }

    private static ChallengeStatusResp status(JsonObject res) {
        JsonElement c = res.get("challenge");
        return c == null || c.isJsonNull() ? ChallengeStatusResp.getDefaultInstance() : ChallengeStatusResp.newBuilder().setChallenge(proto(c.getAsJsonObject())).build();
    }

    /** The same challenge as the JSON the client's push parser reads (property names of the generated class; enums as their ints). */
    private static JsonObject json(JsonObject c) {
        JsonObject o = new JsonObject();
        o.addProperty("ChallengeId", s(c, "id"));
        o.addProperty("ChallengeTitle", s(c, "title"));
        o.addProperty("ChallengeName", s(c, "name"));
        o.addProperty("PlayFirstInternal", playFirst(s(c, "playFirst")));
        o.addProperty("WinConditionInternal", winCondition(s(c, "winCondition")));
        o.addProperty("OwnerPlayerId", s(c, "owner"));
        o.addProperty("ChallengeLimit", c.get("limit").getAsInt());
        JsonArray players = new JsonArray();
        for (JsonElement e : c.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject(), cos = p.getAsJsonObject("cosmetics"), po = new JsonObject(), co = new JsonObject();
            co.addProperty("Avatar", s(cos, "avatar"));
            co.addProperty("Sleeve", s(cos, "sleeve"));
            co.addProperty("Pet", s(cos, "pet"));
            co.addProperty("Title", s(cos, "title"));
            co.add("Emotes", cos.getAsJsonArray("emotes").deepCopy());
            po.addProperty("PlayerId", s(p, "playerId"));
            po.addProperty("DisplayName", s(p, "fullName"));
            po.addProperty("IsWotc", p.has("dev") && p.get("dev").getAsBoolean());
            po.addProperty("Ready", p.get("ready").getAsBoolean());
            po.addProperty("PresenceInternal", 0);
            po.add("Cosmetics", co);
            po.addProperty("DeckArtId", p.get("artId").getAsInt());
            po.addProperty("DeckTileId", p.get("tileId").getAsInt());
            po.addProperty("TeamId", p.get("teamId").getAsInt());
            players.add(po);
        }
        o.add("Players", players);
        return o;
    }
}
