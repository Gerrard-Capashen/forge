package forge.mtga.social;

import com.google.gson.JsonObject;
import forge.mtga.peer.Coordinator;
import forge.mtga.profile.FileProfileStore;

import java.io.IOException;

/**
 * Tells the coordinator how an online match ended for this player ({@code POST /matches/{id}/result}). The coordinator trusts a
 * result only when both seats report the same match (or the relay room saw the opponent drop), see coordinator/src/results.ts.
 * Fire and forget: the game never waits for it, and a failure is only logged (no rewards depend on it yet).
 */
public final class ResultReporter {
    private static final long[] RETRY_MS = {2_000, 10_000, 60_000};

    private final Accounts accounts;
    private final FileProfileStore profiles;
    private volatile String token;
    private volatile long tokenAt;

    public ResultReporter(Accounts accounts, FileProfileStore profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    /** {@code outcome} = "win" | "loss" | "draw" from this player's side; {@code pool} = the queue / event the match was paired in. */
    public void report(Coordinator.Paired paired, String pool, String outcome) {
        Thread t = new Thread(() -> send(paired, pool, outcome), "result-report");
        t.setDaemon(true);
        t.start();
    }

    private void send(Coordinator.Paired paired, String pool, String outcome) {
        JsonObject body = new JsonObject();
        body.addProperty("role", paired.role());
        body.addProperty("token", paired.token());
        body.addProperty("outcome", outcome);
        body.addProperty("pool", pool);
        String path = "/matches/" + paired.match() + "/result";
        for (int attempt = 0; ; attempt++) {
            try {
                JsonObject answer = accounts.call("POST", path, body, token());
                System.out.println("[mtga] result: reported " + outcome + " for match " + paired.match() + " -> " + answer.get("status").getAsString());
                return;
            } catch (Accounts.ApiException e) {
                if (e.status == 401 && attempt == 0) { token = null; continue; }              // the session token expired
                System.out.println("[mtga] result: the coordinator refused the report (" + e.status + " " + e.reason + ")");
                return;
            } catch (IOException e) {
                if (attempt >= RETRY_MS.length) {
                    System.out.println("[mtga] result: could not report match " + paired.match() + " (" + e.getMessage() + ")");
                    return;
                }
                try { Thread.sleep(RETRY_MS[attempt]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private String token() throws IOException {
        if (token == null || System.currentTimeMillis() - tokenAt > 20 * 3_600_000L) {
            token = accounts.login(profiles.load().playerId);
            tokenAt = System.currentTimeMillis();
        }
        return token;
    }
}
