package forge.mtga.social;

import forge.mtga.profile.FileProfileStore;
import forge.mtga.profile.Profile;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Check of the device key and the account sync against a coordinator (default the deployed one; a local `npm run dev` with
 * `-Dcheck.coordinator=http://127.0.0.1:8787` works too). The players it makes are called "zztest ...": the coordinator's
 * `npm run db:clean` removes them. Prints "ACCOUNT CHECK OK" or the failures; exit code 1 on failure.
 */
public final class AccountCheck {
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures.add(what);
    }

    private static void delete(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        dir.delete();
    }

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("check.coordinator", "https://mm.mtga-forge.workers.dev");
        File dir = Files.createTempDirectory("account-check").toFile();
        String run = Integer.toHexString(new Random().nextInt(0xffffff));
        try {
            // the key is made once and survives a restart
            IdentityStore id1 = new IdentityStore(dir);
            IdentityStore id2 = new IdentityStore(dir);
            check(id1.publicKey().equals(id2.publicKey()), "identity.json: same key after a restart");
            File other = Files.createTempDirectory("account-check-2").toFile();
            check(!new IdentityStore(other).publicKey().equals(id1.publicKey()), "another install has another key");
            delete(other);

            // first start: registered, the profile takes the tag the coordinator gave
            FileProfileStore store = new FileProfileStore(dir);
            String name = "zztest java " + run;
            store.update(p -> p.displayName = name);
            Accounts accounts = new Accounts(url, id1);
            Accounts.sync(store, accounts);
            Profile p = store.load();
            check(p.displayName.equals(name) && p.tag != null && p.tag.matches("\\d{5}"), "registered as " + p.clientName());

            // second start: the same name#tag (idempotent)
            String tag = p.tag;
            Accounts.sync(new FileProfileStore(dir), new Accounts(url, new IdentityStore(dir)));
            check(new FileProfileStore(dir).load().tag.equals(tag), "a restart keeps " + name + "#" + tag);

            // renaming in the launcher: another name, another tag
            String renamed = "zztest jv new " + run;
            store.update(q -> q.displayName = renamed);
            Accounts.sync(store, accounts);
            Profile r = store.load();
            check(r.displayName.equals(renamed) && r.tag.matches("\\d{5}"), "renamed to " + r.clientName());

            // a name the coordinator would refuse is made acceptable
            check(Accounts.registrable("a#b").equals("Player ab"), "registrable(\"a#b\") = Player ab");
            check(Accounts.registrable("  Jace   Beleren!! ").equals("Jace Beleren"), "registrable trims and cleans");

            // a player id another key already owns is refused (nobody takes over an identity)
            File thief = Files.createTempDirectory("account-check-3").toFile();
            try {
                Profile stolen = store.load();
                try {
                    new Accounts(url, new IdentityStore(thief)).register(stolen);
                    check(false, "another key cannot register an existing player id");
                } catch (java.io.IOException e) {
                    check(e.getMessage().contains("409"), "another key cannot register an existing player id (" + e.getMessage() + ")");
                }
            } finally {
                delete(thief);
            }

            // the coordinator unreachable: the local profile stays, no exception
            FileProfileStore offline = new FileProfileStore(Files.createTempDirectory("account-check-4").toFile());
            String before = offline.load().clientName();
            Accounts.sync(offline, new Accounts("http://127.0.0.1:9", new IdentityStore(offline.file().getParentFile())));
            check(offline.load().clientName().equals(before), "coordinator unreachable: profile unchanged (" + before + ")");
        } finally {
            delete(dir);
        }
        System.out.println(failures.isEmpty() ? "ACCOUNT CHECK OK" : "ACCOUNT CHECK FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
