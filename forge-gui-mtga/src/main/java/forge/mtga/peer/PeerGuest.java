package forge.mtga.peer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.PublicProfile;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/** Guest side of player-vs-player: connects to the host's {@link PeerHost} and joins the match it is waiting with. */
public final class PeerGuest {
    /** 2: HELLO and HELLO_OK carry the sender's public profile ({@code profile}). */
    public static final int VERSION = 2;

    /** Outcome of {@link #join}: either {@code error} (readable) or the host's public profile and the relay backend for the guest's Match Door connection. */
    public record Joined(String error, String matchId, PublicProfile host, RemoteSeatBackend backend) { }

    private static final Gson GSON = new Gson();

    private PeerGuest() { }

    /** The identity both sides must share: the client's generated card DB (its file name carries the hash of its content). */
    public static String fingerprint(File cardDb) { return cardDb == null ? "?" : cardDb.getName(); }

    public static Joined join(String host, int port, String key, String fingerprint, PublicProfile me,
                              Map<String, Integer> deck, Map<String, Integer> printing) {
        Socket socket = new Socket();
        PeerLink link = null;
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(15_000);
            link = new PeerLink(socket);
            JsonObject hello = new JsonObject();
            hello.addProperty("v", VERSION);
            hello.addProperty("fp", fingerprint);
            hello.addProperty("key", key == null ? "" : key);
            hello.addProperty("name", me.name());
            hello.add("profile", me.toJson());
            hello.add("deck", GSON.toJsonTree(deck));
            hello.add("printing", GSON.toJsonTree(printing));
            link.sendText(PeerLink.HELLO, hello.toString());
            PeerLink.Frame f = link.read();
            if (f == null) return fail(link, "the host closed the connection");
            if (f.type() == PeerLink.REJECT) return fail(link, f.text());
            if (f.type() != PeerLink.HELLO_OK) return fail(link, "unexpected answer from the host");
            JsonObject ok = JsonParser.parseString(f.text()).getAsJsonObject();
            socket.setSoTimeout(0);
            System.out.println("[mtga] peer: joined " + host + ":" + port + ", match " + ok.get("matchId").getAsString());
            PublicProfile theirs = ok.has("profile") && ok.get("profile").isJsonObject() ? PublicProfile.fromJson(ok.getAsJsonObject("profile"))
                    : PublicProfile.fromJson(JsonParser.parseString("{\"name\":" + GSON.toJson(ok.get("name").getAsString()) + "}").getAsJsonObject());
            return new Joined(null, ok.get("matchId").getAsString(), theirs, new RemoteSeatBackend(link));
        } catch (Exception e) {
            return fail(link, "could not reach the host " + host + ":" + port + " (" + e.getMessage() + ")");
        }
    }

    private static Joined fail(PeerLink link, String why) {
        if (link != null) link.close();
        System.out.println("[mtga] peer: join failed: " + why);
        return new Joined(why, null, null, null);
    }
}
