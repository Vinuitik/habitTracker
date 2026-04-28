package habitTracker;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class HabitCompletionSystemTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.dropCollection(Habit.class);
        mongoTemplate.dropCollection(HabitStructure.class);
        mongoTemplate.dropCollection("last_run_date");
        mongoTemplate.dropCollection("_migration");
    }

    @Test
    @WithMockUser
    void completingHabit_returns200_andPersistsStructure() throws Exception {
        mongoTemplate.save(Habit.builder()
                .id(42).name("exercise").frequency(1)
                .startDate(LocalDate.now()).streak(0).longestStreak(0)
                .defaultMade(false).active(true).build());
        mongoTemplate.save(HabitStructure.builder()
                .habitId(42).structureDate(LocalDate.now()).completed(false).build());

        mockMvc.perform(post("/habits/update/42")
                .param("completed", "true")
                .with(csrf()))
                .andExpect(status().isOk());

        HabitStructure structure = mongoTemplate.findOne(
                new Query(Criteria.where("habitId").is(42)
                        .and("structureDate").is(LocalDate.now())),
                HabitStructure.class);
        assertNotNull(structure);
        assertTrue(structure.getCompleted());
    }

    @Test
    @WithMockUser
    void uncompletingHabit_withLastNegativeStreak_restoresStreakInDb() throws Exception {
        mongoTemplate.save(Habit.builder()
                .id(43).name("meditate").frequency(1)
                .startDate(LocalDate.now()).streak(1).longestStreak(1)
                .defaultMade(false).lastNegativeStreak(-27).active(true).build());
        mongoTemplate.save(HabitStructure.builder()
                .habitId(43).structureDate(LocalDate.now()).completed(true).build());

        mockMvc.perform(post("/habits/update/43")
                .param("completed", "false")
                .with(csrf()))
                .andExpect(status().isOk());

        Habit updated = mongoTemplate.findById(43, Habit.class);
        assertNotNull(updated);
        assertEquals(-27, updated.getStreak());
        assertNull(updated.getLastNegativeStreak());
    }
}
