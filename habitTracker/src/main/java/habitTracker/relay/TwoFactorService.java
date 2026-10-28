package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import habitTracker.auth.User;
import habitTracker.auth.UserRepository;
import habitTracker.sync.VaultEncryptionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second factor in front of /relay. A password (or Google) session alone never reaches a runner:
 * the session must also carry a recent TOTP verification for the SAME user.
 *
 * The TOTP secret is stored AES-GCM encrypted (VaultEncryptionService, KPI_SECRETS_KEY). It can't be
 * hashed — the server recomputes codes from it — and Mongo dumps go to Drive, so plaintext is out.
 */
@Service
public class TwoFactorService {

    static final String PENDING = "relay2fa.pendingSecret";
    static final String VERIFIED_USER = "relay2fa.user";
    static final String VERIFIED_UNTIL = "relay2fa.until";
    static final int MAX_FAILURES = 5;
    static final long LOCK_SECONDS = 900;

    public enum Result { OK, WRONG_CODE, LOCKED, NOT_SET_UP }

    private record Attempts(int failures, long lockedUntil) {}

    private final UserRepository users;
    private final TotpService totp;
    private final VaultEncryptionService encryption;
    private final String key;
    private final long ttlSeconds;
    // In-memory, single node: a restart clears lockouts. Fine for one JVM; see relay/FLOWS.md.
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public TwoFactorService(UserRepository users, TotpService totp, VaultEncryptionService encryption,
                            @Value("${kpi.secrets.key:}") String configuredKey,
                            @Value("${jwt.secret}") String jwtSecret,
                            @Value("${relay.2fa.ttl-hours:12}") long ttlHours) {
        this.users = users;
        this.totp = totp;
        this.encryption = encryption;
        this.key = VaultEncryptionService.serverKey(configuredKey, jwtSecret);
        this.ttlSeconds = ttlHours * 3600;
    }

    public Optional<User> currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? Optional.empty() : users.findById(id);
    }

    public boolean isVerified(HttpSession session) {
        String id = SecurityUtils.getCurrentUserId();
        return id != null
                && id.equals(session.getAttribute(VERIFIED_USER))
                && session.getAttribute(VERIFIED_UNTIL) instanceof Long until
                && until > Instant.now().getEpochSecond();
    }

    /** The secret being enrolled. Lives in the session only until a code confirms it. */
    public byte[] pendingSecret(HttpSession session) {
        if (session.getAttribute(PENDING) instanceof byte[] s) return s;
        byte[] s = totp.newSecret();
        session.setAttribute(PENDING, s);
        return s;
    }

    /** NOT_SET_UP if there is no pending secret or the user already enrolled (no silent overwrite). */
    public synchronized Result confirmSetup(HttpSession session, String code) {
        User user = currentUser().orElseThrow();
        if (user.isTotpEnabled() || !(session.getAttribute(PENDING) instanceof byte[] secret)) {
            return Result.NOT_SET_UP;
        }
        Result r = check(user, secret, code);
        if (r != Result.OK) return r;
        user.setTotpSecret(Base64.getEncoder().encodeToString(encryption.encrypt(key, secret)));
        user.setTotpEnabled(true);
        users.save(user);
        session.removeAttribute(PENDING);
        markVerified(session, user);
        return Result.OK;
    }

    // synchronized + user loaded inside: two requests racing with the same code can't both pass.
    public synchronized Result verify(HttpSession session, String code) {
        User user = currentUser().orElseThrow();
        if (!user.isTotpEnabled() || user.getTotpSecret() == null) return Result.NOT_SET_UP;
        byte[] secret = encryption.decrypt(key, Base64.getDecoder().decode(user.getTotpSecret()));
        Result r = check(user, secret, code);
        if (r == Result.OK) {
            users.save(user);
            markVerified(session, user);
        }
        return r;
    }

    private Result check(User user, byte[] secret, String code) {
        long now = Instant.now().getEpochSecond();
        Attempts a = attempts.get(user.getId());
        if (a != null && a.lockedUntil() > now) return Result.LOCKED;
        long step = totp.matchingStep(secret, code, now);
        Long last = user.getTotpLastStep();
        if (step < 0 || (last != null && step <= last)) {
            // An expired lock starts a fresh count.
            int failures = (a == null || a.lockedUntil() != 0 ? 0 : a.failures()) + 1;
            boolean lock = failures >= MAX_FAILURES;
            attempts.put(user.getId(), new Attempts(failures, lock ? now + LOCK_SECONDS : 0));
            return lock ? Result.LOCKED : Result.WRONG_CODE;
        }
        attempts.remove(user.getId());
        user.setTotpLastStep(step);
        return Result.OK;
    }

    private void markVerified(HttpSession session, User user) {
        session.setAttribute(VERIFIED_USER, user.getId());
        session.setAttribute(VERIFIED_UNTIL, Instant.now().getEpochSecond() + ttlSeconds);
    }
}
