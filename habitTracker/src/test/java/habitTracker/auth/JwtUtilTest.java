package habitTracker.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure unit tests for the JWT secret fail-fast guard (no Spring context). */
class JwtUtilTest {

    @Test
    void rejectsInsecureDefaultSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(JwtUtil.INSECURE_DEFAULT_SECRET, 1000));
    }

    @Test
    void rejectsNullOrBlankSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, 1000));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("   ", 1000));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", 1000));
    }

    @Test
    void acceptsStrongUniqueSecret() {
        assertDoesNotThrow(
                () -> new JwtUtil("a-sufficiently-long-unique-secret-0123456789", 1000));
    }
}
