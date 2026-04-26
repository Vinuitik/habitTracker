package habitTracker;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HabitCompletionSystemTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.dropCollection(Habit.class);
        mongoTemplate.dropCollection(HabitStructure.class);
        mongoTemplate.dropCollection("last_run_date");
    }

    private ResponseEntity<String> postCompletion(int habitId, boolean completed) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("completed", String.valueOf(completed));
        return restTemplate.exchange(
                "/habits/update/" + habitId,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    @Test
    void completingHabit_returns200_andPersistsStructure() {
        mongoTemplate.save(Habit.builder()
                .id(42).name("exercise").frequency(1)
                .startDate(LocalDate.now()).streak(0).longestStreak(0)
                .defaultMade(false).active(true).build());
        mongoTemplate.save(HabitStructure.builder()
                .habitId(42).structureDate(LocalDate.now()).completed(false).build());

        ResponseEntity<String> response = postCompletion(42, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        HabitStructure structure = mongoTemplate.findOne(
                new Query(Criteria.where("habitId").is(42)
                        .and("structureDate").is(LocalDate.now())),
                HabitStructure.class);
        assertNotNull(structure);
        assertTrue(structure.getCompleted());
    }

    @Test
    void uncompletingHabit_withLastNegativeStreak_restoresStreakInDb() {
        mongoTemplate.save(Habit.builder()
                .id(43).name("meditate").frequency(1)
                .startDate(LocalDate.now()).streak(1).longestStreak(1)
                .defaultMade(false).lastNegativeStreak(-27).active(true).build());
        mongoTemplate.save(HabitStructure.builder()
                .habitId(43).structureDate(LocalDate.now()).completed(true).build());

        ResponseEntity<String> response = postCompletion(43, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        Habit updated = mongoTemplate.findById(43, Habit.class);
        assertNotNull(updated);
        assertEquals(-27, updated.getStreak());
        assertNull(updated.getLastNegativeStreak());
    }
}
