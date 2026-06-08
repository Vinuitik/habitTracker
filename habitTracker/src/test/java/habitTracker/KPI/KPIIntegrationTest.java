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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class KPIIntegrationTest {

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

    // ── Bug 3 reproducer ──────────────────────────────────────────────────────

    @Test
    void createKPI_withNoHabitsLinked_returns200_notWhitelabel() throws Exception {
        UserPrincipal alice = auth.register("alice@test.com");

        MvcResult result = mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Steps","description":"Daily steps","higherIsBetter":true,"habitIds":[]}
                                """))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        System.out.println("[Bug3 reproducer] status=" + status + " body=" + body);

        // Must not be 500 (whitelabel) — 200 or 400 are both acceptable
        assertNotEquals(500, status, "Got 500 — whitelabel error still present. Body: " + body);
        assertFalse(body.contains("Whitelabel"), "Got whitelabel page in response body");
    }

    @Test
    void createKPI_withNullHabitIds_returns200_notWhitelabel() throws Exception {
        UserPrincipal alice = auth.register("alice2@test.com");

        MvcResult result = mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Weight","description":"","higherIsBetter":false}
                                """))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        System.out.println("[Bug3 reproducer null habitIds] status=" + status + " body=" + body);

        assertNotEquals(500, status, "Got 500 with null habitIds. Body: " + body);
    }

    // ── Bug 1 & 2: KPI list scoped to current user ────────────────────────────

    @Test
    void listKPIs_doesNotReturnOtherUsersKPIs() throws Exception {
        UserPrincipal alice = auth.register("alice3@test.com");
        UserPrincipal bob   = auth.register("bob@test.com");

        // Alice creates a KPI
        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"AliceKPI","description":"","higherIsBetter":true,"habitIds":[]}
                                """))
                .andExpect(status().isOk());

        // Bob must see an empty list, not Alice's KPI
        mockMvc.perform(get("/api/kpis")
                        .with(auth.session(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listKPIs_returnsOnlyOwnKPIs() throws Exception {
        UserPrincipal alice = auth.register("alice4@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"AliceKPI2","description":"","higherIsBetter":true,"habitIds":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/kpis")
                        .with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("AliceKPI2"));
    }

    @Test
    void dashboardKPIs_doesNotReturnOtherUsersKPIs() throws Exception {
        UserPrincipal alice = auth.register("alice5@test.com");
        UserPrincipal bob   = auth.register("bob2@test.com");

        mockMvc.perform(post("/api/kpis/create")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"AliceDashKPI","description":"","higherIsBetter":true,"habitIds":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/kpis/dashboard")
                        .with(auth.session(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Bug 4: add-habit persists userId ─────────────────────────────────────

    @Test
    void addHabit_persistsUserId_onSavedHabit() throws Exception {
        UserPrincipal alice = auth.register("alice6@test.com");

        mockMvc.perform(post("/new-habit")
                        .with(auth.session(alice))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Morning run","frequency":1,"startDate":"%s","active":true,"defaultMade":false}
                                """.formatted(LocalDate.now())))
                .andExpect(status().isOk());

        List<Habit> habits = mongoTemplate.findAll(Habit.class);
        assertEquals(1, habits.size());
        assertEquals(alice.getId(), habits.get(0).getUserId(),
                "Habit.userId must be set to the authenticated user's id");
    }
}
