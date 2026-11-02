package habitTracker.relay;

import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import jakarta.servlet.http.Cookie;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Setup-page UX (phone link, copy key) and "Trust this device" (relay/FLOWS.md "Trusted devices"). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TrustedDeviceTest {

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
        mongoTemplate.remove(new Query(), TrustedDevice.class);
    }

    private long nowStep() {
        return TotpService.stepAt(Instant.now().getEpochSecond());
    }

    private byte[] enroll(UserPrincipal who) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(who)));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(who)).with(csrf())
                .param("code", totp.codeAt(secret, nowStep())));
        return secret;
    }

    /** Verifies in a fresh session with the current code (replay marker rewound so repeat calls work). */
    private MvcResult verify(UserPrincipal who, byte[] secret, boolean trust) throws Exception {
        long now = nowStep();
        mongoTemplate.updateFirst(query(where("_id").is(who.getId())), Update.update("totpLastStep", now - 1), User.class);
        var req = post("/2fa/verify").session(new MockHttpSession()).with(auth.session(who)).with(csrf())
                .param("code", totp.codeAt(secret, now)).param("next", "/relay");
        if (trust) req = req.param("trust", "true");
        return mockMvc.perform(req).andExpect(redirectedUrl("/relay")).andReturn();
    }

    private Cookie trustCookie(UserPrincipal who, byte[] secret) throws Exception {
        Cookie c = verify(who, secret, true).getResponse().getCookie(TrustedDeviceService.COOKIE);
        assertNotNull(c);
        return c;
    }

    @Test
    void setupPageOffersAppLinkAndCopyableKey() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"otpauth://totp/HabitTracker%3Aalice%40example.com?secret=")))
                .andExpect(content().string(containsString("Add to authenticator app")))
                .andExpect(content().string(containsString("Copy setup key")))
                .andExpect(content().string(containsString("name=\"trust\"")))
                .andExpect(content().string(containsString("autocomplete=\"one-time-code\"")));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)))
                .andExpect(content().string(containsString("data-copy=\"" + TotpService.base32(secret) + "\"")));
    }

    @Test
    void cookieOnlyWhenTickedAndOnlyHashStored() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        byte[] secret = enroll(alice);

        assertNull(verify(alice, secret, false).getResponse().getCookie(TrustedDeviceService.COOKIE));
        assertEquals(0, mongoTemplate.count(new Query(), TrustedDevice.class));

        Cookie c = trustCookie(alice, secret);
        assertTrue(c.isHttpOnly());
        assertTrue(c.getSecure());
        assertEquals("/relay", c.getPath());
        assertEquals(30 * 86400, c.getMaxAge());

        String token = c.getValue().substring(c.getValue().indexOf('.') + 1);
        List<Document> rows = mongoTemplate.getCollection("relay_trusted_devices").find().into(new java.util.ArrayList<>());
        assertEquals(1, rows.size());
        assertEquals(DeviceService.hash(token), rows.get(0).getString("tokenHash"));
        assertFalse(rows.get(0).toJson().contains(token), "raw token must never be stored");
    }

    @Test
    void trustOnSetupConfirm() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(alice)).with(csrf())
                        .param("code", totp.codeAt(secret, nowStep())).param("trust", "true"))
                .andExpect(cookie().exists(TrustedDeviceService.COOKIE));
        assertEquals(1, mongoTemplate.count(new Query(), TrustedDevice.class));
    }

    @Test
    void freshSessionWithValidCookieSkipsTheCode() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Cookie c = trustCookie(alice, enroll(alice));

        MockHttpSession fresh = new MockHttpSession();
        mockMvc.perform(get("/relay").session(fresh).cookie(c).with(auth.session(alice))).andExpect(status().isOk());
        mockMvc.perform(get("/relay/api/devices").cookie(c).with(auth.session(alice))).andExpect(status().isOk());
        // Without the cookie, the same kind of fresh session still has to verify.
        mockMvc.perform(get("/relay").session(new MockHttpSession()).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));
    }

    @Test
    void otherUsersCookieIsRejectedAndCleared() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        UserPrincipal bob = auth.register("bob@example.com");
        Cookie c = trustCookie(alice, enroll(alice));
        enroll(bob);
        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(c).with(auth.session(bob)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"))
                .andExpect(cookie().maxAge(TrustedDeviceService.COOKIE, 0));
    }

    @Test
    void expiredCookieIsRejected() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Cookie c = trustCookie(alice, enroll(alice));
        mongoTemplate.updateMulti(new Query(), Update.update("expiresAt", Instant.now().minusSeconds(5)), TrustedDevice.class);
        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(c).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"))
                .andExpect(cookie().maxAge(TrustedDeviceService.COOKIE, 0));
    }

    @Test
    void tamperedCookieIsRejected() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Cookie c = trustCookie(alice, enroll(alice));
        String v = c.getValue();
        char last = v.charAt(v.length() - 1);
        for (String bad : List.of(v.substring(0, v.length() - 1) + (last == 'A' ? 'B' : 'A'),
                v.substring(0, v.indexOf('.')), "nonsense", "000000000000000000000000." + v.substring(v.indexOf('.') + 1))) {
            mockMvc.perform(get("/relay").session(new MockHttpSession())
                            .cookie(new Cookie(TrustedDeviceService.COOKIE, bad)).with(auth.session(alice)))
                    .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));
        }
    }

    @Test
    void forgetDeletesAllRowsForTheUser() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        UserPrincipal bob = auth.register("bob@example.com");
        byte[] secret = enroll(alice);
        Cookie c1 = trustCookie(alice, secret);
        trustCookie(alice, secret);
        Cookie bobs = trustCookie(bob, enroll(bob));

        MockHttpSession s = new MockHttpSession();
        mockMvc.perform(get("/relay/api/trusted-devices").session(s).cookie(c1).with(auth.session(alice)))
                .andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.days").value(30));
        mockMvc.perform(delete("/relay/api/trusted-devices").session(s).with(auth.session(alice)).with(csrf()))
                .andExpect(jsonPath("$.removed").value(2))
                .andExpect(cookie().maxAge(TrustedDeviceService.COOKIE, 0));

        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(c1).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));
        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(bobs).with(auth.session(bob)))
                .andExpect(status().isOk());
    }

    @Test
    void reEnrolmentForgetsTrustedDevices() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Cookie c = trustCookie(alice, enroll(alice));
        // Manual 2FA reset (FLOWS "Not yet"): unset the enrolment, then enrol again.
        mongoTemplate.updateFirst(query(where("_id").is(alice.getId())),
                new Update().set("totpEnabled", false).unset("totpSecret").unset("totpLastStep"), User.class);
        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(c).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/setup?next=%2Frelay"));
        enroll(alice);
        assertEquals(0, mongoTemplate.count(new Query(), TrustedDevice.class));
        mockMvc.perform(get("/relay").session(new MockHttpSession()).cookie(c).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));
    }

    @Test
    void verifyPageShowsCheckbox() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        enroll(alice);
        mockMvc.perform(get("/2fa/verify").session(new MockHttpSession()).with(auth.session(alice)))
                .andExpect(content().string(containsString("Trust this device for 30 days")))
                .andExpect(content().string(not(containsString("otpauth://"))));
    }
}
