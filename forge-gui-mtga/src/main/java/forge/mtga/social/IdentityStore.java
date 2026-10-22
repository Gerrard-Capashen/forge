package forge.mtga.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The device key that proves who this install is to the coordinator: an ECDSA P-256 key pair in {@code identity.json} in the data
 * directory, made on first use. The private key never leaves the machine; losing the file loses the identity (the launcher
 * offers a backup). See docs/online-identity-friends.md.
 */
public final class IdentityStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final PrivateKey privateKey;
    private final String publicKey;

    public IdentityStore(File dataDir) {
        this.file = new File(dataDir, "identity.json");
        KeyPair pair = null;
        PrivateKey priv = null;
        String pub = null;
        if (file.isFile()) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                priv = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(o.get("privateKey").getAsString())));
                pub = o.get("publicKey").getAsString();
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                throw new IllegalStateException("identity.json is unreadable (" + e + "): restore it from a backup or delete it to start a new identity: " + file, e);
            }
        } else {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"));
                pair = g.generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            priv = pair.getPrivate();
            pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            JsonObject o = new JsonObject();
            o.addProperty("version", 1);
            o.addProperty("algorithm", "ECDSA P-256");
            o.addProperty("privateKey", Base64.getEncoder().encodeToString(priv.getEncoded()));
            o.addProperty("publicKey", pub);
            write(o);
            System.out.println("[mtga] identity: new device key " + file);
        }
        this.privateKey = priv;
        this.publicKey = pub;
    }

    private void write(JsonObject o) {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file + ": " + e, e);
        }
    }

    /** SPKI DER, base64: what the coordinator stores and verifies against. */
    public String publicKey() { return publicKey; }

    /** Signature over the UTF-8 text, IEEE P1363 (r||s), base64: what WebCrypto verifies. */
    public String sign(String message) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(privateKey);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
