package habitTracker.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    // The placeholder shipped as the compose/properties fallback. If it ever reaches runtime it
    // means JWT_SECRET was not set, and anyone reading this public repo could forge valid tokens.
    static final String INSECURE_DEFAULT_SECRET = "changeme-at-least-32-chars-long-secret";
    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a >=256-bit key

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-ms}") long expirationMs) {
        if (secret == null || secret.isBlank() || INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "JWT_SECRET is unset or still the insecure default. Set a strong, unique JWT_SECRET "
                + "(>=32 chars) in your environment/.env before starting the app.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256 signing.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generate(UserPrincipal principal) {
        return Jwts.builder()
                .subject(principal.getUsername())
                .claim("userId", principal.getId())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public boolean validate(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public String extractUserId(String token) {
        return getClaims(token).get("userId", String.class);
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
