package habitTracker.sync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

// AES-256-GCM using a real random per-user key (generated once at Drive-connect time) rather
// than a human passphrase — no PBKDF2 needed since the key is already high-entropy. Wire format:
// [12B IV][ciphertext + 16B GCM tag]. No compression — mailbox payloads are small JSON batches.
@Service
public class VaultEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    // Server at-rest key (see serverKey()) for encryptAtRest/decryptAtRest. null only in unit tests
    // that build this with the no-arg constructor and never touch at-rest secrets.
    private final String atRestKey;

    public VaultEncryptionService() {
        this.atRestKey = null;
    }

    @Autowired
    public VaultEncryptionService(@Value("${kpi.secrets.key:}") String configuredKey,
                                  @Value("${jwt.secret}") String jwtSecret) {
        this.atRestKey = serverKey(configuredKey, jwtSecret);
    }

    public String generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, RANDOM);
            return Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate encryption key", e);
        }
    }

    public byte[] encrypt(String base64Key, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyFrom(base64Key), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] wire = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, wire, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, wire, IV_BYTES, ciphertext.length);
            return wire;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(String base64Key, byte[] wire) {
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(wire, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyFrom(base64Key), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(wire, IV_BYTES, wire.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed (wrong key or corrupted file)", e);
        }
    }

    /**
     * The server's own at-rest key (Trello / Drive sync secrets, TOTP secrets): KPI_SECRETS_KEY if set, else derived
     * from jwt.secret. The derivation label predates other users of this key; changing it would make
     * already-stored secrets undecryptable.
     */
    public static String serverKey(String configuredKey, String jwtSecret) {
        if (configuredKey != null && !configuredKey.isBlank()) return configuredKey.trim();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("kpi-capability-secrets|" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encryptString(String base64Key, String plaintext) {
        return encrypt(base64Key, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encrypts a secret for storage in Mongo under the server key: base64 of the wire format.
     * null stays null. Used for UserSyncSettings / TrelloCredential.
     */
    public String encryptAtRest(String plaintext) {
        if (plaintext == null) return null;
        return Base64.getEncoder().encodeToString(encryptString(requireAtRestKey(), plaintext));
    }

    /** Inverse of encryptAtRest(). Throws IllegalStateException if the server key changed. */
    public String decryptAtRest(String stored) {
        if (stored == null) return null;
        return new String(decrypt(requireAtRestKey(), Base64.getDecoder().decode(stored)), StandardCharsets.UTF_8);
    }

    private String requireAtRestKey() {
        if (atRestKey == null) throw new IllegalStateException("At-rest key not configured");
        return atRestKey;
    }

    private SecretKey keyFrom(String base64Key) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }
}
