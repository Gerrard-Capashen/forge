package forge.mtga.peer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.PublicProfile;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/** Guest side of player-vs-player: connects to the host's {@link PeerHost} and joins the match it is waiting with. */
public final class PeerGuest {
    /** 2: HELLO and HELLO_OK carry the sender's public profile ({@code profile}). */
    public static final int VERSION = 2;

    /** Outcome of {@link #join}: either {@code error} (readable) or the host's public profile and the relay backend for the guest's Match Door connection. */
    public record Joined(String error, String matchId, PublicProfile host, RemoteSeatBackend backend, List<String> hostCommanders) { }

    private static final Gson GSON = new Gson();

    private PeerGuest() { }

    /** The identity both sides must share: the client's generated card DB (its file name carries the hash of its content). */
    public static String fingerprint(File cardDb) { return cardDb == null ? "?" : cardDb.getName(); }

    public static Joined join(String host, int port, String key, String fingerprint, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing) {
        return join(host, port, key, fingerprint, "", me, deck, printing);
    }

    /** {@code queue}: the queue event this guest plays in; the host only accepts a guest of the queue it waits in. */
    public static Joined join(String host, int port, String key, String fingerprint, String queue, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing) {
        return join(host, port, key, fingerprint, queue, me, deck, printing, Map.of());
    }

    public static Joined join(String host, int port, String key, String fingerprint, String queue, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard) {
        return join(host, port, key, fingerprint, queue, me, deck, printing, sideboard, null, List.of());
    }

    /** With the deck's companion (a card name or null), which the host's engine plays for this seat. */
    public static Joined join(String host, int port, String key, String fingerprint, String queue, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion) {
        return join(host, port, key, fingerprint, queue, me, deck, printing, sideboard, companion, List.of());
    }

    /** With the deck's commanders (its command zone), which the host's engine puts in this seat's command zone. */
    public static Joined join(String host, int port, String key, String fingerprint, String queue, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion, List<String> commanders) {
        PeerLink link = null;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            link = new PeerLink(socket);
            return join(link, host + ":" + port, fingerprint, key, queue, me, deck, printing, sideboard, companion, commanders);
        } catch (Exception e) {
            return fail(link, "could not reach the host " + host + ":" + port + " (" + e.getMessage() + ")");
        }
    }

    /** Online: the host the coordinator paired this guest with is on the other end of {@code link} (a room). */
    public static Joined joinOnline(PeerLink link, String fingerprint, String queue, PublicProfile me,
                                    Map<String, Integer> deck, Map<String, Integer> printing) {
        return joinOnline(link, fingerprint, queue, me, deck, printing, Map.of());
    }

    /** With the sideboard the guest plays between the games of a best-of-three (the host decides how many games there are). */
    public static Joined joinOnline(PeerLink link, String fingerprint, String queue, PublicProfile me,
                                    Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard) {
        return joinOnline(link, fingerprint, queue, me, deck, printing, sideboard, null, List.of());
    }

    public static Joined joinOnline(PeerLink link, String fingerprint, String queue, PublicProfile me,
                                    Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion) {
        return joinOnline(link, fingerprint, queue, me, deck, printing, sideboard, companion, List.of());
    }

    public static Joined joinOnline(PeerLink link, String fingerprint, String queue, PublicProfile me,
                                    Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion, List<String> commanders) {
        return join(link, "the coordinator room", fingerprint, "", queue, me, deck, printing, sideboard, companion, commanders);
    }

    private static Joined join(PeerLink link, String where, String fingerprint, String key, String queue, PublicProfile me,
                               Map<String, Integer> deck, Map<String, Integer> printing, Map<String, Integer> sideboard, String companion, List<String> commanders) {
        try {
            link.setReadTimeout(15_000);
            JsonObject hello = new JsonObject();
            hello.addProperty("v", VERSION);
            hello.addProperty("fp", fingerprint);
            hello.addProperty("key", key == null ? "" : key);
            hello.addProperty("queue", queue == null ? "" : queue);
            hello.addProperty("name", me.name());
            hello.add("profile", me.toJson());
            hello.add("deck", GSON.toJsonTree(deck));
            hello.add("printing", GSON.toJsonTree(printing));
            hello.add("sideboard", GSON.toJsonTree(sideboard));
            if (companion != null && !companion.isBlank()) hello.addProperty("companion", companion);
            if (commanders != null && !commanders.isEmpty()) hello.add("commanders", GSON.toJsonTree(commanders));
            link.sendText(PeerLink.HELLO, hello.toString());
            PeerLink.Frame f = link.read();
            if (f == null) return fail(link, "the host closed the connection");
            if (f.type() == PeerLink.REJECT) return fail(link, f.text());
            if (f.type() != PeerLink.HELLO_OK) return fail(link, "unexpected answer from the host");
            JsonObject ok = JsonParser.parseString(f.text()).getAsJsonObject();
            link.setReadTimeout(0);
            System.out.println("[mtga] peer: joined " + where + ", match " + ok.get("matchId").getAsString());
            PublicProfile theirs = ok.has("profile") && ok.get("profile").isJsonObject() ? PublicProfile.fromJson(ok.getAsJsonObject("profile"))
                    : PublicProfile.fromJson(JsonParser.parseString("{\"name\":" + GSON.toJson(ok.get("name").getAsString()) + "}").getAsJsonObject());
            List<String> hostCommanders = new java.util.ArrayList<>();
            if (ok.has("commanders") && ok.get("commanders").isJsonArray()) for (var c : ok.getAsJsonArray("commanders")) hostCommanders.add(c.getAsString());
            return new Joined(null, ok.get("matchId").getAsString(), theirs, new RemoteSeatBackend(link), hostCommanders);
        } catch (Exception e) {
            return fail(link, "could not reach the host at " + where + " (" + e.getMessage() + ")");
        }
    }

    private static Joined fail(PeerLink link, String why) {
        if (link != null) link.close();
        System.out.println("[mtga] peer: join failed: " + why);
        return new Joined(why, null, null, null, List.of());
    }
}
