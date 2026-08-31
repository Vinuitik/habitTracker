package habitTracker.sync;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Device-pairing one-time codes — same in-memory-nonce shape as DriveOAuthService.pendingStates,
// extended to carry a userId (the OAuth state nonce doesn't need one; it's tied to the browser
// session that's mid-flow) and single-use redemption (OAuth state is consumed once too, via the
// same remove()-is-atomic trick — this mirrors that instead of inventing a new pattern).
//
// Single-instance in-memory store — fine for a single javaapp container (no clustering). A
// backend restart mid-pairing just means the user clicks "Pair a device" again.
@Service
public class PairingCodeService {

    private static final long DEFAULT_TTL_MS = 10 * 60 * 1000; // 10 min, matches DriveOAuthService.STATE_TTL_MS
    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L — human-typed
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final long ttlMs;
    private final Map<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();

    public PairingCodeService() {
        this(DEFAULT_TTL_MS);
    }

    // Package-private: lets tests use a short TTL instead of sleeping 10 minutes to prove expiry.
    PairingCodeService(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public String generateCode(String userId) {
        String code = randomCode();
        pendingCodes.put(code, new PendingCode(userId, System.currentTimeMillis() + ttlMs));
        return code;
    }

    /**
     * Redeems a code: valid + unexpired codes return the owning userId and are removed
     * immediately (single-use) — map.remove() is atomic, so a code can never be redeemed twice
     * even under concurrent requests. An expired code is also removed (cleanup) but yields empty.
     */
    public Optional<String> redeem(String code) {
        PendingCode pending = pendingCodes.remove(code);
        if (pending == null || pending.expiryMs() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(pending.userId());
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private record PendingCode(String userId, long expiryMs) {}
}
