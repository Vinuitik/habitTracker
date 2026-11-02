package habitTracker.relay;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * "Trust this device for N days": after a successful TOTP check the browser gets a {@code relay_trust} cookie
 * ({@code <rowId>.<token>}); while it is valid for the logged-in user, TwoFactorInterceptor skips the code.
 * It never replaces the HabitTracker login — it only stands in for the second factor.
 */
@Service
public class TrustedDeviceService {

    static final String COOKIE = "relay_trust";
    // Only /relay/** is gated (TwoFactorInterceptor), so that's the only place the browser needs to send it.
    // Responses from /2fa/** can still set it: Set-Cookie's Path doesn't have to match the request path.
    static final String COOKIE_PATH = "/relay";
    static final int TOKEN_BYTES = 32;
    static final int MAX_USER_AGENT = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongo;
    private final long days;

    public TrustedDeviceService(MongoTemplate mongo, @Value("${relay.2fa.trust-days:30}") long days) {
        this.mongo = mongo;
        this.days = Math.max(0, days);
    }

    /** 0 days = feature off: checkbox hidden, cookies ignored. */
    public long days() {
        return days;
    }

    // spring.data.mongodb.auto-index-creation is off, so indexes are created here. The TTL index lets Mongo
    // delete expired rows on its own (its sweeper runs every ~60s); validity is still checked on read.
    @PostConstruct
    void ensureIndexes() {
        var ops = mongo.indexOps(TrustedDevice.class);
        ops.ensureIndex(new Index("tokenHash", Sort.Direction.ASC).unique());
        ops.ensureIndex(new Index("userId", Sort.Direction.ASC));
        ops.ensureIndex(new Index("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    /** Stores a new trusted device for userId and sets the cookie on the response. No-op when the feature is off. */
    public void trust(String userId, HttpServletRequest req, HttpServletResponse res) {
        if (days == 0) return;
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now();
        String ua = req.getHeader(HttpHeaders.USER_AGENT);
        TrustedDevice d = mongo.insert(TrustedDevice.builder()
                .userId(userId)
                .tokenHash(DeviceService.hash(token))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofDays(days)))
                .userAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), MAX_USER_AGENT)))
                .build());
        setCookie(res, d.getId() + "." + token, Duration.ofDays(days));
    }

    /**
     * True if the request carries a valid, unexpired trust cookie issued to userId. A cookie that is present
     * but bad (unknown, tampered, expired, another user's, or feature off) is cleared.
     */
    public boolean accepts(String userId, HttpServletRequest req, HttpServletResponse res) {
        String value = cookieValue(req);
        if (value == null) return false;
        if (days > 0 && userId != null && valid(userId, value)) return true;
        setCookie(res, "", Duration.ZERO);
        return false;
    }

    private boolean valid(String userId, String value) {
        int dot = value.indexOf('.');
        if (dot <= 0 || dot == value.length() - 1) return false;
        TrustedDevice d = mongo.findById(value.substring(0, dot), TrustedDevice.class);
        if (d == null || d.getTokenHash() == null) return false;
        boolean hashOk = MessageDigest.isEqual(
                DeviceService.hash(value.substring(dot + 1)).getBytes(US_ASCII), d.getTokenHash().getBytes(US_ASCII));
        return hashOk && userId.equals(d.getUserId())
                && d.getExpiresAt() != null && d.getExpiresAt().isAfter(Instant.now());
    }

    public long count(String userId) {
        return mongo.count(query(where("userId").is(userId).and("expiresAt").gt(Instant.now())), TrustedDevice.class);
    }

    /** Deletes every trusted device of userId (and clears this browser's cookie when a response is given). */
    public long forgetAll(String userId, HttpServletResponse res) {
        long removed = mongo.remove(query(where("userId").is(userId)), TrustedDevice.class).getDeletedCount();
        if (res != null) setCookie(res, "", Duration.ZERO);
        return removed;
    }

    private static String cookieValue(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName()) && !c.getValue().isEmpty()) return c.getValue();
        }
        return null;
    }

    private static void setCookie(HttpServletResponse res, String value, Duration maxAge) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path(COOKIE_PATH).maxAge(maxAge).build().toString());
    }
}
