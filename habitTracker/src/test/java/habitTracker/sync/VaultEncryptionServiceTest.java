package habitTracker.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

// M6 requires proving that the capability-deploy channel correctly REUSES this existing
// AES-256-GCM service rather than inventing new crypto. This test targets VaultEncryptionService
// directly (no capability-specific code involved) since that's the thing being reused.
class VaultEncryptionServiceTest {

    private final VaultEncryptionService service = new VaultEncryptionService();

    @Test
    void encryptThenDecrypt_roundTripsExactly() {
        String key = service.generateKey();
        byte[] plaintext = "{\"capabilityId\":\"cap-x\",\"version\":1,\"sourceCode\":\"print(1)\"}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] wire = service.encrypt(key, plaintext);
        byte[] decrypted = service.decrypt(key, wire);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void wireFormat_isTwelveByteIvPlusCiphertextPlusSixteenByteTag() {
        String key = service.generateKey();
        byte[] plaintext = "hello capability payload".getBytes(StandardCharsets.UTF_8);

        byte[] wire = service.encrypt(key, plaintext);

        assertEquals(12 + plaintext.length + 16, wire.length, "wire = 12B IV + ciphertext + 16B GCM tag");
    }

    @Test
    void decrypt_withWrongKey_throws() {
        String key = service.generateKey();
        String wrongKey = service.generateKey();
        byte[] wire = service.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> service.decrypt(wrongKey, wire));
    }

    @Test
    void encrypt_isNonDeterministic_randomIvPerCall() {
        String key = service.generateKey();
        byte[] plaintext = "same plaintext".getBytes(StandardCharsets.UTF_8);

        byte[] wireA = service.encrypt(key, plaintext);
        byte[] wireB = service.encrypt(key, plaintext);

        assertFalse(Arrays.equals(wireA, wireB), "IV must be fresh per encryption call");
    }
}
