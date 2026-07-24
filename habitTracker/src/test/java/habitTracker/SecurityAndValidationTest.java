package habitTracker;

import habitTracker.Habit.Habit;
import habitTracker.Rules.Rule;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the security/validation hardening:
 *  - sign-up auto-login redirect target
 *  - bean validation on habit creation
 *  - IDOR: a user cannot read/edit/delete/complete another user's habit
 *  - register input validation
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SecurityAndValidationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;

    AuthTestHelper auth;

    @BeforeEach
    void clear() {
        mongoTemplate.dropCollection(Habit.class);
        mongoTemplate.dropCollection(HabitStructure.class);
        mongoTemplate.dropCollection(User.class);
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
    }

    private Habit saveHabitFor(UserPrincipal owner, int id, String name) {
        Habit h = Habit.builder()
                .id(id).name(name).frequency(1)
                .startDate(LocalDate.now()).streak(3).longestStreak(3)
                .defaultMade(false).active(true).userId(owner.getId()).build();
        return mongoTemplate.save(h);
    }

    // ---------- sign-up redirect ----------

    @Test
    void register_withValidInput_redirectsToTodayNotLanding() throws Exception {
        mockMvc.perform(post("/register")
                        .param("email", "newbie@habits.test")
                        .param("password", "longenoughpw")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/today"));
    }

    // ---------- register validation ----------

    @Test
    void register_withShortPassword_rerendersWithErrorAndCreatesNoUser() throws Exception {
        mockMvc.perform(post("/register")
                        .param("email", "shortpw@habits.test")
                        .param("password", "123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeExists("error"));

        assertEquals(0, mongoTemplate.findAll(User.class).size());
    }

    @Test
    void register_withMalformedEmail_rerendersWithError() throws Exception {
        mockMvc.perform(post("/register")
                        .param("email", "not-an-email")
                        .param("password", "longenoughpw")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }

    // ---------- habit creation validation ----------

    @Test
    void createHabit_withBlankName_returns400() throws Exception {
        UserPrincipal alice = auth.register("alice@habits.test");
        mockMvc.perform(post("/new-habit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":1,\"startDate\":\"2026-06-19\"}")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createHabit_withNonPositiveFrequency_returns400() throws Exception {
        UserPrincipal alice = auth.register("alice2@habits.test");
        mockMvc.perform(post("/new-habit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Read\",\"frequency\":-2,\"startDate\":\"2026-06-19\"}")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createHabit_withValidInput_returns200() throws Exception {
        UserPrincipal alice = auth.register("alice3@habits.test");
        mockMvc.perform(post("/new-habit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Read\",\"frequency\":1,\"startDate\":\"2026-06-19\"}")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk());
    }

    // ---------- IDOR: cross-user access ----------

    @Test
    void cannotReadAnotherUsersHabitById() throws Exception {
        UserPrincipal alice = auth.register("owner@habits.test");
        UserPrincipal bob = auth.register("attacker@habits.test");
        saveHabitFor(alice, 100, "alice-secret-habit");

        mockMvc.perform(get("/api/habits/100").with(auth.session(bob)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("alice-secret-habit"))));
    }

    @Test
    void cannotReadAnotherUsersStreaks() throws Exception {
        UserPrincipal alice = auth.register("owner2@habits.test");
        UserPrincipal bob = auth.register("attacker2@habits.test");
        saveHabitFor(alice, 101, "alice-habit");

        mockMvc.perform(post("/habits/streaks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[101]")
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void cannotCompleteAnotherUsersHabit() throws Exception {
        UserPrincipal alice = auth.register("owner3@habits.test");
        UserPrincipal bob = auth.register("attacker3@habits.test");
        saveHabitFor(alice, 102, "alice-habit");

        mockMvc.perform(post("/habits/update/102")
                        .param("completed", "true")
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isBadRequest());

        // No completion record should have been written for the victim's habit.
        assertEquals(0, mongoTemplate.findAll(HabitStructure.class).size());
    }

    @Test
    void cannotDeleteAnotherUsersHabit() throws Exception {
        UserPrincipal alice = auth.register("owner4@habits.test");
        UserPrincipal bob = auth.register("attacker4@habits.test");
        saveHabitFor(alice, 103, "alice-habit");

        mockMvc.perform(delete("/habits/delete/103")
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isNotFound());

        Habit still = mongoTemplate.findById(103, Habit.class);
        assertNotNull(still);
        assertTrue(still.getActive(), "victim's habit must remain active");
    }

    @Test
    void ownerCanStillCompleteOwnHabit() throws Exception {
        UserPrincipal alice = auth.register("owner5@habits.test");
        saveHabitFor(alice, 104, "alice-habit");

        mockMvc.perform(post("/habits/update/104")
                        .param("completed", "true")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk());
    }

    // Bug: updateHabitCompletion() never set userId on newly-created HabitStructure rows, so a
    // fresh toggle looked "saved" but was invisible to any userId-scoped read (e.g. the history
    // table), reverting to unchecked on reload. Regression guard: the written row must carry it.
    @Test
    void completingHabit_persistsUserIdOnHabitStructure() throws Exception {
        UserPrincipal alice = auth.register("owner6@habits.test");
        saveHabitFor(alice, 105, "alice-habit");

        mockMvc.perform(post("/habits/update/105")
                        .param("completed", "true")
                        .with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk());

        List<HabitStructure> written = mongoTemplate.findAll(HabitStructure.class);
        assertEquals(1, written.size());
        assertEquals(alice.getId(), written.get(0).getUserId(),
                "HabitStructure.userId must be set so userId-scoped reads (history views) find it");
    }

    // ---------- IDOR: planting a Rule on another user's habit ----------

    @Test
    void cannotAddRuleOnAnotherUsersHabit() throws Exception {
        UserPrincipal alice = auth.register("owner7@habits.test");
        UserPrincipal bob = auth.register("attacker7@habits.test");
        saveHabitFor(alice, 106, "alice-habit");
        saveHabitFor(bob, 206, "bob-habit");

        mongoTemplate.dropCollection(Rule.class);

        mockMvc.perform(post("/habits/addRule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainId\":106,\"subIds\":[206],\"frequency\":1,\"streak\":0}")
                        .with(auth.session(bob)).with(csrf()))
                .andExpect(status().isNotFound());

        // No Rule row should exist pointing at the victim's habit — planting it before the
        // ownership check used to succeed even though the request overall was rejected.
        assertEquals(0, mongoTemplate.findAll(Rule.class).size());
    }
}
