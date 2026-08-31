package habitTracker.sync;

import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Exercises the server side of the M5 device-pairing handshake described in
// docs/designs/kpi-tracking-agent.md and src/main/java/habitTracker/sync/FLOWS.md:
// generate-pairing-code (session-authed) -> pair (deliberately unauthenticated).
//
// The companion device's OWN Google OAuth-for-installed-apps exchange (step 3 of the design
// doc's pairing section) never touches this Java controller at all — it's a direct HTTP call
// from the Python companion straight to Google's token endpoint. That leg is covered instead by
// tools/companion/test_pair.py against a locally mocked Google token endpoint (no real Google
// credentials exist in this environment). This test covers everything that DOES happen
// server-side: a UserSyncSettings row is seeded directly (representing a user who already
// completed the existing, separately-tested Drive-connect OAuth flow) rather than re-driving
// that flow here.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PairingIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(UserSyncSettings.class);
        mongoTemplate.dropCollection(habitTracker.auth.User.class);
    }

    @Test
    void fullHandshake_generateCode_thenPair_returnsMailboxCredentials() throws Exception {
        UserPrincipal alice = auth.register("alice-pair1@test.com");
        mongoTemplate.save(UserSyncSettings.builder()
                .userId(alice.getId())
                .driveRefreshToken("refresh-token-not-used-by-pairing")
                .driveFolderId("root-folder-id")
                .mailboxFolderId("mailbox-folder-id")
                .encryptionKey("dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcyE=")
                .build());

        // Step 1: browser, logged in as Alice, requests a pairing code.
        MvcResult genResult = mockMvc.perform(post("/api/sync/generate-pairing-code")
                        .with(auth.session(alice))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> genBody = objectMapper.readValue(genResult.getResponse().getContentAsString(), Map.class);
        String code = (String) genBody.get("code");
        assertNotNull(code);
        assertEquals(600, ((Number) genBody.get("expiresInSeconds")).intValue());

        // Step 2: the companion, on a different device with NO session and NO CSRF token,
        // redeems the code.
        mockMvc.perform(post("/api/sync/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxFolderId").value("mailbox-folder-id"))
                .andExpect(jsonPath("$.encryptionKey").value("dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcyE="));
    }

    @Test
    void pair_sameCodeTwice_secondAttemptFails() throws Exception {
        UserPrincipal bob = auth.register("bob-pair1@test.com");
        mongoTemplate.save(UserSyncSettings.builder()
                .userId(bob.getId())
                .driveRefreshToken("rt")
                .mailboxFolderId("mailbox-bob")
                .encryptionKey("key-bob")
                .build());

        MvcResult genResult = mockMvc.perform(post("/api/sync/generate-pairing-code")
                        .with(auth.session(bob))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String code = (String) objectMapper.readValue(genResult.getResponse().getContentAsString(), Map.class).get("code");

        mockMvc.perform(post("/api/sync/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sync/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or expired code"));
    }

    @Test
    void pair_unknownCode_returns400() throws Exception {
        mockMvc.perform(post("/api/sync/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOTAREALCODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or expired code"));
    }

    @Test
    void generatePairingCode_withoutDriveConnected_returns409() throws Exception {
        UserPrincipal carol = auth.register("carol-pair1@test.com");

        mockMvc.perform(post("/api/sync/generate-pairing-code")
                        .with(auth.session(carol))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void generatePairingCode_withoutSession_isRejected() throws Exception {
        // The session/web filter chain (SecurityConfig.webFilterChain) redirects unauthenticated
        // requests to /login via formLogin's default entry point rather than a bare 401 — same
        // behavior every other anyRequest().authenticated() route in this chain already has
        // (e.g. /api/sync/disconnect, /api/sync/status). The controller's own null-userId check
        // is unreachable in practice for this chain; it's a defensive backstop, not what fires.
        mockMvc.perform(post("/api/sync/generate-pairing-code").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void pair_isReachableWithoutSessionOrCsrfToken() throws Exception {
        // Regression guard for the SecurityConfig wiring: /api/sync/pair must be both permitAll
        // AND csrf-ignored, or a legitimate companion call gets rejected before ever reaching
        // PairingCodeService. A missing/invalid code still 400s (never 401/403) here.
        mockMvc.perform(post("/api/sync/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WHATEVER1\"}"))
                .andExpect(status().isBadRequest());
    }
}
