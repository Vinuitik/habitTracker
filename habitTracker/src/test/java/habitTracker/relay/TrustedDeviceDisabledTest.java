package habitTracker.relay;

import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RELAY_2FA_TRUST_DAYS=0: no checkbox, ticking is ignored, existing cookies don't skip the code. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "relay.2fa.trust-days=0")
@AutoConfigureMockMvc
@Testcontainers
class TrustedDeviceDisabledTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired TotpService totp;

    @Test
    void featureOff() throws Exception {
        AuthTestHelper auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(User.class);
        UserPrincipal alice = auth.register("alice@example.com");

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/2fa/setup").session(session).with(auth.session(alice)))
                .andExpect(content().string(not(containsString("name=\"trust\""))));
        byte[] secret = (byte[]) session.getAttribute(TwoFactorService.PENDING);
        mockMvc.perform(post("/2fa/setup").session(session).with(auth.session(alice)).with(csrf())
                        .param("code", totp.codeAt(secret, TotpService.stepAt(Instant.now().getEpochSecond())))
                        .param("trust", "true"))
                .andExpect(cookie().doesNotExist(TrustedDeviceService.COOKIE));
        assertEquals(0, mongoTemplate.count(new Query(), TrustedDevice.class));

        mockMvc.perform(get("/2fa/verify").session(new MockHttpSession()).with(auth.session(alice)))
                .andExpect(content().string(not(containsString("name=\"trust\""))));

        // A row + cookie left over from when the feature was on: ignored.
        String token = "left-over-token";
        TrustedDevice d = mongoTemplate.insert(TrustedDevice.builder().userId(alice.getId())
                .tokenHash(DeviceService.hash(token)).createdAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(5))).build());
        mockMvc.perform(get("/relay").session(new MockHttpSession())
                        .cookie(new Cookie(TrustedDeviceService.COOKIE, d.getId() + "." + token)).with(auth.session(alice)))
                .andExpect(redirectedUrl("/2fa/verify?next=%2Frelay"));
    }
}
