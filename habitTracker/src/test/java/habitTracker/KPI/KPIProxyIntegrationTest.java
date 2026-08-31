package habitTracker.KPI;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import habitTracker.updater.KPIProxyFillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end for the proxy mechanism's DI wiring: create a KPI wired to ProxyType.TRELLO_CARD_COUNT
 * (which the real ProxyProviderRegistry now resolves to TrelloCardCountProvider — see M2), run one
 * pass of the nightly proxy-fill step, and verify it degrades gracefully (writes nothing, doesn't
 * throw) when no Trello credentials are on file for the user — exactly the "one user's bad/missing
 * Trello setup can't break the nightly batch for everyone else" requirement. The actual successful
 * fetch-and-write path (real credentials + a fake Trello server) is covered end-to-end by
 * TrelloCardCountProviderIntegrationTest instead, to keep this file's Testcontainers-only setup as
 * the lightweight version.
 * A plain KPI (proxyType defaults to NONE) is included alongside it to confirm the step leaves
 * ordinary manual KPIs untouched, matching KPIProxyFillServiceTest's unit-level regression check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class KPIProxyIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired KPIProxyFillService kpiProxyFillService;

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

    @Test
    void proxyFillPass_noTrelloCredentials_skipsGracefully_andLeavesManualKPIUntouched() throws Exception {
        UserPrincipal alice = auth.register("alice-proxy1@test.com");

        // Wired to TRELLO_CARD_COUNT, but alice has never connected a Trello account —
        // TrelloCardCountProvider must resolve via the registry and then no-op, not throw.
        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards","higherIsBetter":true,"habitIds":[],"proxyType":"TRELLO_CARD_COUNT",
                                 "proxyConfig":{"boardId":"board-1","listId":"list-1"}}
                                """))
                .andExpect(status().isOk());

        // Plain manual KPI (proxyType defaults to NONE) — must stay untouched by the proxy step
        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Weight","higherIsBetter":false,"habitIds":[]}
                                """))
                .andExpect(status().isOk());

        // One full nightly-job pass of the proxy step — must not throw despite alice having no
        // Trello credentials on file.
        kpiProxyFillService.fillFromProxies();

        mockMvc.perform(get("/api/kpis/Cards/data").param("period", "alltime")
                        .with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/kpis/Weight/data").param("period", "alltime")
                        .with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createKPI_withProxyType_persistsAndReturnsIt() throws Exception {
        UserPrincipal alice = auth.register("alice-proxy2@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards2","higherIsBetter":true,"habitIds":[],"proxyType":"TRELLO_CARD_COUNT"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/kpis").with(auth.session(alice)))
                .andExpect(jsonPath("$[0].proxyType").value("TRELLO_CARD_COUNT"));
    }

    @Test
    void updateProxySettings_persistsAndReturnsUpdatedKPI() throws Exception {
        UserPrincipal alice = auth.register("alice-proxy3@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards3","higherIsBetter":true,"habitIds":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/kpis/Cards3/proxy")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"proxyType":"TRELLO_CARD_COUNT","proxyConfig":{"boardId":"b1"},"confirmSampleRate":0.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proxyType").value("TRELLO_CARD_COUNT"))
                .andExpect(jsonPath("$.confirmSampleRate").value(0.5));
    }
}
