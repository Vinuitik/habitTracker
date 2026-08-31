package habitTracker.KPI;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M12: end-to-end for the manual reset endpoint (PUT /api/kpis/{name}/proxy/reset) — the one
 * fully-real "repair" path until the real auto-repair pipeline exists (needs M8). Trips a KPI's
 * circuit breaker directly via Mongo (there's no public endpoint to do that — it's normally only
 * ProxyHealthService, driven by the nightly proxy-fill step, covered separately by
 * ProxyHealthServiceTest and KPIProxyFillServiceTest), then verifies the reset endpoint clears it
 * back to ACTIVE and enforces per-user ownership the same way every other KPI mutation does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProxyHealthIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(Habit.class);
        mongoTemplate.dropCollection(HabitStructure.class);
        mongoTemplate.dropCollection(User.class);
        mongoTemplate.dropCollection("_migration");
    }

    private void tripCircuitBreaker(String kpiName, String userId) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("name").is(kpiName).and("userId").is(userId)),
                new Update().set("proxyStatus", ProxyStatus.NEEDS_REPAIR).set("consecutiveProxyFailures", 3),
                KPI.class);
    }

    @Test
    void resetProxyHealth_clearsNeedsRepairBackToActive_andZeroesFailureCounter() throws Exception {
        UserPrincipal alice = auth.register("alice-reset1@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards","higherIsBetter":true,"habitIds":[],"proxyType":"TRELLO_CARD_COUNT"}
                                """))
                .andExpect(status().isOk());

        tripCircuitBreaker("Cards", alice.getId());

        // Confirm the trip took, so the reset assertion below is meaningful.
        mockMvc.perform(get("/api/kpis").with(auth.session(alice)))
                .andExpect(jsonPath("$[0].proxyStatus").value("NEEDS_REPAIR"))
                .andExpect(jsonPath("$[0].consecutiveProxyFailures").value(3));

        mockMvc.perform(put("/api/kpis/Cards/proxy/reset")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proxyStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.consecutiveProxyFailures").value(0));

        mockMvc.perform(get("/api/kpis").with(auth.session(alice)))
                .andExpect(jsonPath("$[0].proxyStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[0].consecutiveProxyFailures").value(0));
    }

    @Test
    void resetProxyHealth_cannotResetAnotherUsersKpi() throws Exception {
        UserPrincipal alice = auth.register("alice-reset2@test.com");
        UserPrincipal bob = auth.register("bob-reset2@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards","higherIsBetter":true,"habitIds":[],"proxyType":"TRELLO_CARD_COUNT"}
                                """))
                .andExpect(status().isOk());

        tripCircuitBreaker("Cards", alice.getId());

        // Bob has no KPI named "Cards" — his own userId scoping means this must not touch
        // Alice's KPI, regardless of the name matching.
        mockMvc.perform(put("/api/kpis/Cards/proxy/reset")
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isBadRequest());

        // Alice's KPI must still be broken — Bob's attempt had zero effect on it.
        mockMvc.perform(get("/api/kpis").with(auth.session(alice)))
                .andExpect(jsonPath("$[0].proxyStatus").value("NEEDS_REPAIR"))
                .andExpect(jsonPath("$[0].consecutiveProxyFailures").value(3));
    }
}
