package habitTracker.sync;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

    public byte[] encryptString(String base64Key, String plaintext) {
        return encrypt(base64Key, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey keyFrom(String base64Key) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }
}
