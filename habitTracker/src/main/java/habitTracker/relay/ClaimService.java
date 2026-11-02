package habitTracker.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Phone-first pairing (shared/HUB.md "QR pairing"): the runner starts a claim and shows its link as a QR,
 * a logged-in + 2FA browser approves it, the runner polls with its secret and receives the device token.
 * In memory only: a restart drops pending claims (the runner then reports "declined or expired").
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    static final int TTL_SECONDS = 600;
    static final int MAX_PENDING = 100;
    // 20 chars of a 32-letter alphabet = 100 bits: the id is a bearer link, never typed, so it can't be guessable.
    static final int ID_LENGTH = 20;
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceService devices;
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();

    public record Started(String claimId, String pollSecret) {}

    public enum PollStatus { PENDING, APPROVED, GONE }

    public record PollResult(PollStatus status, DeviceService.Paired paired) {}

    private static final class Claim {
        final String pollHash;
        final String name;
        final Instant expiresAt;
        DeviceService.Paired paired; // guarded by this

        Claim(String pollHash, String name, Instant expiresAt) {
            this.pollHash = pollHash;
            this.name = name;
            this.expiresAt = expiresAt;
        }

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** Runner side, unauthenticated. Empty when too many claims are pending (caller answers 429). */
    public Optional<Started> start(String name) {
        claims.values().removeIf(Claim::expired);
        if (claims.size() >= MAX_PENDING) return Optional.empty();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id = newId();
        claims.put(id, new Claim(DeviceService.hash(secret), DeviceService.cleanName(name),
                Instant.now().plusSeconds(TTL_SECONDS)));
        return Optional.of(new Started(id, secret));
    }

    /** Browser: what the confirm page shows. Empty for unknown, expired or already-approved claims. */
    public Optional<String> pendingName(String claimId) {
        return live(claimId).filter(c -> {
            synchronized (c) {
                return c.paired == null;
            }
        }).map(c -> c.name);
    }

    /** Browser: binds the claim to the approving user and creates the device. Empty if gone or already approved. */
    public Optional<DeviceService.Paired> approve(String userId, String claimId) {
        return live(claimId).flatMap(c -> {
            synchronized (c) {
                if (c.paired != null) return Optional.empty();
                c.paired = devices.create(userId, c.name);
                return Optional.of(c.paired);
            }
        });
    }

    /** Browser: "Not mine" — the runner's next poll gets GONE. */
    public boolean deny(String claimId) {
        return live(claimId).map(c -> {
            synchronized (c) {
                return c.paired == null && claims.remove(claimId, c);
            }
        }).orElse(false);
    }

    /** Runner side. The token is handed out once: an APPROVED poll removes the claim. */
    public PollResult poll(String claimId, String pollSecret) {
        Optional<Claim> found = live(claimId).filter(c -> pollSecret != null
                && MessageDigest.isEqual(c.pollHash.getBytes(UTF_8), DeviceService.hash(pollSecret).getBytes(UTF_8)));
        if (found.isEmpty()) return new PollResult(PollStatus.GONE, null);
        Claim c = found.get();
        synchronized (c) {
            if (c.paired == null) return new PollResult(PollStatus.PENDING, null);
            claims.remove(claimId, c);
            return new PollResult(PollStatus.APPROVED, c.paired);
        }
    }

    private Optional<Claim> live(String claimId) {
        if (claimId == null) return Optional.empty();
        Claim c = claims.get(claimId);
        if (c == null) return Optional.empty();
        if (c.expired()) {
            claims.remove(claimId, c);
            return Optional.empty();
        }
        return Optional.of(c);
    }

    private static String newId() {
        char[] out = new char[ID_LENGTH];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(out);
    }
}
