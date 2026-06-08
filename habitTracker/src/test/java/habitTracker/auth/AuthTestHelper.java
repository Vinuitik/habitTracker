package habitTracker.auth;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Creates real User documents in the test Mongo container and produces
 * genuine UserPrincipal / JWT tokens — no @WithMockUser, no mocked principals.
 *
 * Usage (session/web chain):
 *   mockMvc.perform(get("/api/kpis").with(auth.session(alice)))
 *
 * Usage (API/JWT chain):
 *   mockMvc.perform(get("/api/...").header("Authorization", auth.bearer(alice)))
 */
public class AuthTestHelper {

    private final MongoTemplate mongo;
    private final JwtUtil jwtUtil;

    public AuthTestHelper(MongoTemplate mongo, JwtUtil jwtUtil) {
        this.mongo = mongo;
        this.jwtUtil = jwtUtil;
    }

    /** Saves a User to Mongo and returns a real UserPrincipal (with a DB-assigned id). */
    public UserPrincipal register(String email) {
        User user = User.builder()
                .email(email)
                .passwordHash("irrelevant-for-session-tests")
                .name("Test User")
                .build();
        user = mongo.save(user);
        return UserPrincipal.fromUser(user);
    }

    /** RequestPostProcessor for MockMvc — injects principal into the security context. */
    public RequestPostProcessor session(UserPrincipal principal) {
        return SecurityMockMvcRequestPostProcessors.user(principal);
    }

    /** Generates a real signed JWT for the given principal (for /api/** chain tests). */
    public String bearer(UserPrincipal principal) {
        return "Bearer " + jwtUtil.generate(principal);
    }
}
