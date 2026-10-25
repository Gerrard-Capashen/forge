package forge.mtga.social;

import com.google.gson.JsonObject;
import forge.mtga.profile.FileProfileStore;

import java.io.IOException;

/**
 * The player's signed-in session with the coordinator: a session token (24 h, renewed after 20 h or when the coordinator says 401)
 * and REST calls with it. Shared by the services that act as the player (rank, mastery).
 */
public final class CoordinatorSession {
    private final Accounts accounts;
    private final FileProfileStore profiles;
    private String token;
    private long tokenAt;

    public CoordinatorSession(Accounts accounts, FileProfileStore profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    /** The session token (signs in when there is none or it is old). */
    public synchronized String token() throws IOException {
        if (token == null || System.currentTimeMillis() - tokenAt > 20 * 3_600_000L) {
            token = accounts.login(profiles.load().playerId);
            tokenAt = System.currentTimeMillis();
        }
        return token;
    }

    /** One REST call as the player; an expired token is renewed once. */
    public JsonObject call(String method, String path, JsonObject body) throws IOException {
        try {
            return accounts.call(method, path, body, token());
        } catch (Accounts.ApiException e) {
            if (e.status != 401) throw e;
            synchronized (this) { token = null; }
            return accounts.call(method, path, body, token());
        }
    }

    /** A public call (no token). */
    public JsonObject publicCall(String path) throws IOException { return accounts.call("GET", path, null, null); }
}
