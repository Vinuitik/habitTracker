package habitTracker.updater;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreakCalculationServiceUnitTest {

    @Mock
    MongoTemplate mongoTemplate;

    private StreakCalculationService service;

    @BeforeEach
    void setUp() {
        service = new StreakCalculationService(mongoTemplate, new HabitDateCalculator());
    }

    // --- helpers ---

    private Habit dailyHabit(int streak, boolean defaultMade) {
        return Habit.builder()
                .id(1)
                .name("test")
                .frequency(1)
                .startDate(LocalDate.of(2020, 1, 1))
                .streak(streak)
                .longestStreak(Math.max(streak, 0))
                .defaultMade(defaultMade)
                .active(true)
                .build();
    }

    private Habit weeklyHabit(int streak, LocalDate startDate) {
        return Habit.builder()
                .id(1)
                .name("test")
                .frequency(7)
                .startDate(startDate)
                .streak(streak)
                .longestStreak(Math.max(streak, 0))
                .defaultMade(false)
                .active(true)
                .build();
    }

    private void stubWith(Habit habit, List<HabitStructure> structures) {
        when(mongoTemplate.find(any(Query.class), eq(Habit.class))).thenReturn(List.of(habit));
        when(mongoTemplate.find(any(Query.class), eq(HabitStructure.class))).thenReturn(structures);
    }

    private Update captureUpdate() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(), eq(Habit.class));
        return captor.getValue();
    }

    private int streakFrom(Update update) {
        Document set = (Document) update.getUpdateObject().get("$set");
        return ((Number) set.get("streak")).intValue();
    }

    private Integer lastNegFrom(Update update) {
        Document set = (Document) update.getUpdateObject().get("$set");
        Object val = set.get("lastNegativeStreak");
        return val == null ? null : ((Number) val).intValue();
    }

    private boolean lastNegUnset(Update update) {
        Document unset = (Document) update.getUpdateObject().get("$unset");
        return unset != null && unset.containsKey("lastNegativeStreak");
    }

    // --- fast path: countScheduledDays via delta ---

    @Test
    void fastPath_dailyHabit_7dayGap_streakDecreases7() {
        stubWith(dailyHabit(0, false), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(7));
        assertEquals(-7, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_dailyHabit_7dayGap_defaultMadeTrue_streakIncreases7() {
        stubWith(dailyHabit(0, true), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(7));
        assertEquals(7, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_positiveStreak_missedDays_resetsToOneMinusDelta() {
        // streak=3, 5 missed days → 1 - 5 = -4
        stubWith(dailyHabit(3, false), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(5));
        assertEquals(-4, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_negativeStreak_missedMoreDays_continuesDecrementing() {
        // streak=-3, 4 more missed days → -3 - 4 = -7
        stubWith(dailyHabit(-3, false), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(4));
        assertEquals(-7, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_weeklyHabit_14dayGap_counts2ScheduledDays() {
        // startDate=today-14, lastRun=today-14 → scheduled: day0=today-14, day7=today-7 → 2 days
        LocalDate start = LocalDate.now().minusDays(14);
        stubWith(weeklyHabit(0, start), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(14));
        assertEquals(-2, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_weeklyHabit_gapStartsMidCycle_alignsToNextScheduledDay() {
        // startDate=today-10, lastRun=today-7 (3 days into cycle)
        // next scheduled in gap: today-10+7=today-3 → 1 day
        LocalDate start = LocalDate.now().minusDays(10);
        stubWith(weeklyHabit(0, start), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(7));
        assertEquals(-1, streakFrom(captureUpdate()));
    }

    @Test
    void fastPath_positiveStreak_missedDays_setsLastNegUnset() {
        stubWith(dailyHabit(3, false), List.of());
        service.updateAllStreaks(LocalDate.now().minusDays(5));
        assertTrue(lastNegUnset(captureUpdate()));
    }

    // --- slow path: explicit completions and transitions ---

    @Test
    void slowPath_explicitCompletion_fromNegative_streakBecomesOne() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        HabitStructure s = HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(true).build();
        stubWith(dailyHabit(-5, false), List.of(s));

        service.updateAllStreaks(yesterday);

        assertEquals(1, streakFrom(captureUpdate()));
    }

    @Test
    void slowPath_explicitCompletion_fromNegative_savesLastNegativeStreak() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        HabitStructure s = HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(true).build();
        stubWith(dailyHabit(-27, false), List.of(s));

        service.updateAllStreaks(yesterday);

        assertEquals(-27, lastNegFrom(captureUpdate()));
    }

    @Test
    void slowPath_notCompleted_fromPositive_streakDropsToZero_clearsLastNeg() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        HabitStructure s = HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(false).build();
        stubWith(dailyHabit(3, false), List.of(s));

        service.updateAllStreaks(yesterday);

        Update update = captureUpdate();
        assertEquals(0, streakFrom(update));
        assertTrue(lastNegUnset(update));
    }

    @Test
    void slowPath_notCompleted_alreadyNegative_continuesDecrementing() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        HabitStructure s = HabitStructure.builder()
                .habitId(1).structureDate(yesterday).completed(false).build();
        stubWith(dailyHabit(-4, false), List.of(s));

        service.updateAllStreaks(yesterday);

        assertEquals(-5, streakFrom(captureUpdate()));
    }

    @Test
    void slowPath_inactiveHabit_isSkippedEntirely() {
        Habit inactive = Habit.builder()
                .id(1).frequency(1).startDate(LocalDate.of(2020, 1, 1))
                .streak(0).longestStreak(0).defaultMade(false).active(false).build();
        when(mongoTemplate.find(any(Query.class), eq(Habit.class))).thenReturn(List.of());

        service.updateAllStreaks(LocalDate.now().minusDays(1));

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(Habit.class));
    }
}
