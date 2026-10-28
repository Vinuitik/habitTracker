package habitTracker.relay;

import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TwoFactorFlowTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired TotpService totp;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(User.class);
    }

    private String currentCode(byte[] secret, int stepOffset) {
        return totp.codeAt(secret, TotpService.stepAt(Instant.now().getEpochSecond()) + stepOffset);
    }

    /** Enrolls {@code who} through the real setup form and returns the secret their app would hold. */
    private byte[] enroll(UserPrincipal who) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(who))).andExpect(status().isOk());
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(who)).with(csrf())
                        .param("code", currentCode(secret, 0)).param("next", "/relay"))
                .andExpect(redirectedUrl("/relay"));
        return secret;
    }

    @Test
    void anonymousGoesToLogin() throws Exception {
        mockMvc.perform(get("/relay")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void notEnrolledGoesToSetup() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        mockMvc.perform(get("/relay").with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/setup?next=%2Frelay"));
    }

    @Test
    void setupStoresSecretEncryptedAndOpensRelay() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)))
                .andExpect(status().isOk()).andExpect(model().attributeExists("qrSvg"));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);

        // Wrong code: same secret re-shown, nothing saved.
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(alice)).with(csrf())
                        .param("code", "000000"))
                .andExpect(status().isOk()).andExpect(view().name("twofa-setup"))
                .andExpect(model().attribute("secret", TotpService.base32(secret).replaceAll("(.{4})(?!$)", "$1 ")));
        assertFalse(mongoTemplate.findById(alice.getId(), User.class).isTotpEnabled());

        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(alice)).with(csrf())
                        .param("code", currentCode(secret, 0)).param("next", "/relay"))
                .andExpect(redirectedUrl("/relay"));

        User saved = mongoTemplate.findById(alice.getId(), User.class);
        assertTrue(saved.isTotpEnabled());
        assertFalse(saved.getTotpSecret().contains(TotpService.base32(secret)));
        byte[] wire = Base64.getDecoder().decode(saved.getTotpSecret());
        assertEquals(12 + TotpService.SECRET_BYTES + 16, wire.length, "IV + ciphertext + GCM tag");

        mockMvc.perform(get("/relay").session(session).with(auth.session(alice))).andExpect(status().isOk());
    }

    @Test
    void enrolledUserMustVerifyAndCodesCantBeReplayed() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        byte[] secret = enroll(alice);

        MockHttpSession fresh = new MockHttpSession();
        mockMvc.perform(get("/relay").session(fresh).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));

        // The code used to enroll is spent; the next step's code still works (drift window).
        long spent = mongoTemplate.findById(alice.getId(), User.class).getTotpLastStep();
        mockMvc.perform(post("/2fa/verify").session(fresh).with(auth.session(alice)).with(csrf())
                        .param("code", totp.codeAt(secret, spent)))
                .andExpect(view().name("twofa-verify")).andExpect(model().attributeExists("error"));
        mockMvc.perform(post("/2fa/verify").session(fresh).with(auth.session(alice)).with(csrf())
                        .param("code", totp.codeAt(secret, spent + 1)).param("next", "/relay/x"))
                .andExpect(redirectedUrl("/relay/x"));
        mockMvc.perform(get("/relay").session(fresh).with(auth.session(alice))).andExpect(status().isOk());
    }

    @Test
    void locksAfterFiveWrongCodes() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        byte[] secret = enroll(alice);
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < TwoFactorService.MAX_FAILURES; i++) {
            mockMvc.perform(post("/2fa/verify").session(session).with(auth.session(alice)).with(csrf())
                    .param("code", "000000"));
        }
        mockMvc.perform(post("/2fa/verify").session(session).with(auth.session(alice)).with(csrf())
                        .param("code", currentCode(secret, 1)))
                .andExpect(model().attribute("error", "Too many wrong codes. Try again in 15 minutes."));
    }

    @Test
    void verificationIsBoundToTheUser() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        UserPrincipal bob = auth.register("bob@example.com");
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(alice)).with(csrf())
                .param("code", currentCode(secret, 0)));

        mockMvc.perform(get("/relay").session(session).with(auth.session(bob)))
                .andExpect(redirectedUrl("/2fa/setup?next=%2Frelay"));
    }

    @Test
    void nextIsAlwaysSameSite() {
        assertEquals("/relay/devices", TwoFactorController.safeNext("/relay/devices"));
        assertEquals("/relay", TwoFactorController.safeNext(null));
        assertEquals("/relay", TwoFactorController.safeNext("//evil.example"));
        assertEquals("/relay", TwoFactorController.safeNext("https://evil.example"));
        assertEquals("/relay", TwoFactorController.safeNext("/\\evil.example"));
    }
}
