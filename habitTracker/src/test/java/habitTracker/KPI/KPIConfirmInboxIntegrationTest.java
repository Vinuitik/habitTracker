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
import org.springframework.http.MediaType;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M3: MVC-level test for the confirm-inbox endpoints (GET /api/kpis/pending, PUT
 * /{name}/data/{date}/confirm, PUT /{name}/data/{date}) hitting the real controller + service +
 * Mongo, not just the service layer directly. Proves the pending -> cleared transition and the
 * source-on-edit business rule work end to end, and that ownership is enforced: a user can't
 * confirm or edit another user's KPIData, including through a same-named KPI (which resolves to
 * a different id-keyed collection, same pattern as every other KPI endpoint's cross-user tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class KPIConfirmInboxIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired KPICollectionNameUtil collectionNameUtil;
    @Autowired DynamicKPIDataRepository dynamicKPIDataRepository;

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

    /** Creates a KPI via the real endpoint and returns its persisted id (from Mongo directly). */
    private String createKpiAndGetId(UserPrincipal user, String name) throws Exception {
        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","higherIsBetter":true,"habitIds":[]}
                                """.formatted(name)))
                .andExpect(status().isOk());

        KPI kpi = mongoTemplate.findOne(
                new Query(Criteria.where("name").is(name).and("userId").is(user.getId())), KPI.class);
        assertNotNull(kpi, "KPI should have been persisted");
        return kpi.getId();
    }

    /** Seeds a pending proxy-sourced KPIData point directly into the KPI's own collection. */
    private void seedPendingData(String kpiId, LocalDate date, double value) {
        String collectionName = collectionNameUtil.toCollectionName(kpiId);
        KPIData data = KPIData.builder()
                .date(date).value(value)
                .source(KPIDataSource.PROXY_TRELLO).pending(true)
                .build();
        dynamicKPIDataRepository.save(data, collectionName);
    }

    @Test
    void pendingEndpoint_returnsOnlyCurrentUsersPendingPoints() throws Exception {
        UserPrincipal alice = auth.register("alice-inbox1@test.com");
        UserPrincipal bob   = auth.register("bob-inbox1@test.com");

        String aliceKpiId = createKpiAndGetId(alice, "Cards");
        String bobKpiId   = createKpiAndGetId(bob, "Cards"); // same name, different KPI/collection

        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(aliceKpiId, date, 5.0);
        seedPendingData(bobKpiId, date, 999.0);

        mockMvc.perform(get("/api/kpis/pending").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kpiName").value("Cards"))
                .andExpect(jsonPath("$[0].value").value(5.0))
                .andExpect(jsonPath("$[0].pending").value(true));
    }

    @Test
    void confirmEndpoint_clearsPending_keepsSourceAndValue() throws Exception {
        UserPrincipal alice = auth.register("alice-inbox2@test.com");
        String kpiId = createKpiAndGetId(alice, "Cards");
        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(kpiId, date, 5.0);

        mockMvc.perform(put("/api/kpis/Cards/data/{date}/confirm", date)
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.source").value("PROXY_TRELLO"))
                .andExpect(jsonPath("$.value").value(5.0));

        mockMvc.perform(get("/api/kpis/pending").with(auth.session(alice)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void editEndpoint_overwritesValue_clearsPending_setsSourceManual() throws Exception {
        UserPrincipal alice = auth.register("alice-inbox3@test.com");
        String kpiId = createKpiAndGetId(alice, "Cards");
        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(kpiId, date, 5.0);

        mockMvc.perform(put("/api/kpis/Cards/data/{date}", date)
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": 9.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.value").value(9.0));

        mockMvc.perform(get("/api/kpis/Cards/data").param("period", "alltime")
                        .with(auth.session(alice)))
                .andExpect(jsonPath("$[0].value").value(9.0))
                .andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[0].pending").value(false));
    }

    @Test
    void confirmEndpoint_cannotConfirmAnotherUsersKPIData_evenWithSameKpiName() throws Exception {
        UserPrincipal alice = auth.register("alice-inbox4@test.com");
        UserPrincipal bob   = auth.register("bob-inbox4@test.com");

        String aliceKpiId = createKpiAndGetId(alice, "Cards");
        createKpiAndGetId(bob, "Cards"); // Bob owns a same-named KPI, different collection

        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(aliceKpiId, date, 5.0);

        // Bob attempts to confirm using the shared KPI name — must resolve to *his own* (empty)
        // collection, not Alice's, and fail rather than touching her data.
        mockMvc.perform(put("/api/kpis/Cards/data/{date}/confirm", date)
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isBadRequest());

        // Alice's point is untouched
        mockMvc.perform(get("/api/kpis/pending").with(auth.session(alice)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pending").value(true));
    }

    @Test
    void editEndpoint_neverWritesIntoAnotherUsersKPIData_evenWithSameKpiName() throws Exception {
        // Bob owns his own "Cards" KPI (distinct id-keyed collection from Alice's), so his edit
        // call legitimately resolves to *his own* KPI and succeeds — but it must land only in
        // his own collection and never touch Alice's, exactly like every other same-named-KPI
        // isolation test in this codebase (e.g. KPIIntegrationTest#sameNamedKPI_dataIsIsolated...).
        UserPrincipal alice = auth.register("alice-inbox5@test.com");
        UserPrincipal bob   = auth.register("bob-inbox5@test.com");

        String aliceKpiId = createKpiAndGetId(alice, "Cards");
        createKpiAndGetId(bob, "Cards");

        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(aliceKpiId, date, 5.0);

        mockMvc.perform(put("/api/kpis/Cards/data/{date}", date)
                        .with(auth.session(bob)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": 12345.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(12345.0));

        // Alice's original point is completely untouched
        mockMvc.perform(get("/api/kpis/Cards/data").param("period", "alltime")
                        .with(auth.session(alice)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value(5.0))
                .andExpect(jsonPath("$[0].source").value("PROXY_TRELLO"));
    }

    @Test
    void editEndpoint_rejectsWhenCallerHasNoSuchKPIAtAll() throws Exception {
        UserPrincipal alice = auth.register("alice-inbox6@test.com");
        UserPrincipal bob   = auth.register("bob-inbox6@test.com");

        String aliceKpiId = createKpiAndGetId(alice, "Cards");
        LocalDate date = LocalDate.now().minusDays(1);
        seedPendingData(aliceKpiId, date, 5.0);

        // Bob has no "Cards" KPI of his own at all — must be rejected outright.
        mockMvc.perform(put("/api/kpis/Cards/data/{date}", date)
                        .with(auth.session(bob)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": 12345.0}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/kpis/Cards/data").param("period", "alltime")
                        .with(auth.session(alice)))
                .andExpect(jsonPath("$[0].value").value(5.0));
    }
}
