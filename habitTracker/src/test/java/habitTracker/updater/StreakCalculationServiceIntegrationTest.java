package habitTracker.updater;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class StreakCalculationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    StreakCalculationService streakService;

    @Autowired
    MongoTemplate mongoTemplate;

    // Prevents @PostConstruct from running and interfering with test data
    @MockBean
    UpdateScheduler updateScheduler;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.dropCollection(Habit.class);
        mongoTemplate.dropCollection(HabitStructure.class);
        mongoTemplate.dropCollection("last_run_date");
    }

    private Habit savedDailyHabit(int id, int streak, boolean defaultMade) {
        Habit habit = Habit.builder()
                .id(id)
                .name("habit-" + id)
                .frequency(1)
                .startDate(LocalDate.of(2020, 1, 1))
                .streak(streak)
                .longestStreak(Math.max(streak, 0))
                .defaultMade(defaultMade)
                .active(true)
                .build();
        mongoTemplate.save(habit);
        return habit;
    }

    // --- downtime catch-up (fast path) ---

    @Test
    void downtime7Days_defaultMadeFalse_streakDecreases7() {
        savedDailyHabit(1, 0, false);

        streakService.updateAllStreaks(LocalDate.now().minusDays(7));

        assertEquals(-7, mongoTemplate.findById(1, Habit.class).getStreak());
    }

    @Test
    void downtime7Days_defaultMadeTrue_streakIncreases7_andUpdatesLongest() {
        savedDailyHabit(1, 0, true);

        streakService.updateAllStreaks(LocalDate.now().minusDays(7));

        Habit updated = mongoTemplate.findById(1, Habit.class);
        assertEquals(7, updated.getStreak());
        assertEquals(7, updated.getLongestStreak());
    }

    @Test
    void downtimeAfterPositiveStreak_streakResetsCorrectly() {
        // streak=5, 3 missed days → 1 - 3 = -2
        savedDailyHabit(1, 5, false);

        streakService.updateAllStreaks(LocalDate.now().minusDays(3));

        assertEquals(-2, mongoTemplate.findById(1, Habit.class).getStreak());
    }

    // --- slow path: explicit completion transitions ---

    @Test
    void explicitCompletion_fromNegative_streakBecomesOne() {
        savedDailyHabit(1, -5, false);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        mongoTemplate.save(HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(true).build());

        streakService.updateAllStreaks(yesterday);

        assertEquals(1, mongoTemplate.findById(1, Habit.class).getStreak());
    }

    @Test
    void explicitCompletion_fromNegative_savesLastNegativeStreak() {
        savedDailyHabit(1, -27, false);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        mongoTemplate.save(HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(true).build());

        streakService.updateAllStreaks(yesterday);

        Habit updated = mongoTemplate.findById(1, Habit.class);
        assertEquals(1, updated.getStreak());
        assertEquals(-27, updated.getLastNegativeStreak());
    }

    @Test
    void positiveStreak_missedDay_streakDropsToZero_clearsLastNegativeStreak() {
        Habit habit = Habit.builder()
                .id(1).name("h").frequency(1).startDate(LocalDate.of(2020, 1, 1))
                .streak(5).longestStreak(5).defaultMade(false)
                .lastNegativeStreak(-10).active(true).build();
        mongoTemplate.save(habit);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        mongoTemplate.save(HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(false).build());

        streakService.updateAllStreaks(yesterday);

        Habit updated = mongoTemplate.findById(1, Habit.class);
        assertEquals(0, updated.getStreak());
        assertNull(updated.getLastNegativeStreak());
    }

    @Test
    void inactiveHabit_streakNeverChanges() {
        Habit inactive = Habit.builder()
                .id(1).name("h").frequency(1).startDate(LocalDate.of(2020, 1, 1))
                .streak(0).longestStreak(0).defaultMade(false).active(false).build();
        mongoTemplate.save(inactive);

        streakService.updateAllStreaks(LocalDate.now().minusDays(7));

        assertEquals(0, mongoTemplate.findById(1, Habit.class).getStreak());
    }

    @Test
    void multipleHabits_eachUpdatedIndependently() {
        savedDailyHabit(1, 0, false);  // → -7
        savedDailyHabit(2, 0, true);   // → +7

        streakService.updateAllStreaks(LocalDate.now().minusDays(7));

        assertEquals(-7, mongoTemplate.findById(1, Habit.class).getStreak());
        assertEquals(7, mongoTemplate.findById(2, Habit.class).getStreak());
    }
}
