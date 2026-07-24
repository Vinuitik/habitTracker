package habitTracker.updater;

import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructureRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the rolling grace-window engine. "today" is LocalDate.now(); occurrences are
 * positioned by setting curDate relative to now.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HabitUpdateServiceUnitTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock HabitStructureManager habitStructureManager;
    @Mock HabitStructureRepository habitStructureRepository;

    private HabitUpdateService service() {
        return new HabitUpdateService(mongoTemplate, habitStructureManager, habitStructureRepository);
    }

    private static final LocalDate TODAY = LocalDate.now();

    private Habit habit(int freq, int streak, boolean defaultMade, LocalDate curDate) {
        return Habit.builder()
                .id(1).name("test").frequency(freq).startDate(curDate).curDate(curDate)
                .streak(streak).longestStreak(Math.max(streak, 0))
                .defaultMade(defaultMade).active(true).build();
    }

    private void run(Habit h) {
        when(mongoTemplate.findAll(Habit.class)).thenReturn(List.of(h));
        service().updateAllHabits();
    }

    private Update captureUpdate() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(), eq(Habit.class));
        return captor.getValue();
    }

    private int streakOf(Update u) {
        Document set = (Document) u.getUpdateObject().get("$set");
        return ((Number) set.get("streak")).intValue();
    }

    private LocalDate curDateOf(Update u) {
        Document set = (Document) u.getUpdateObject().get("$set");
        return (LocalDate) set.get("curDate");
    }

    private Integer lastNegOf(Update u) {
        Document set = (Document) u.getUpdateObject().get("$set");
        Object v = set.get("lastNegativeStreak");
        return v == null ? null : ((Number) v).intValue();
    }

    private boolean lastNegUnset(Update u) {
        Document unset = (Document) u.getUpdateObject().get("$unset");
        return unset != null && unset.containsKey("lastNegativeStreak");
    }

    // --- daily (frequency = 1) ---

    @Test
    void dailyMiss_docksToMinusOne() {
        // yesterday's window is closed & uncompleted -> -1 on the update day
        run(habit(1, 0, false, TODAY.minusDays(1)));
        assertEquals(-1, streakOf(captureUpdate()));
    }

    @Test
    void positiveStreak_singleLapse_resetsToZero_clearsLastNeg() {
        Update u = null;
        run(habit(1, 3, false, TODAY.minusDays(1)));
        u = captureUpdate();
        assertEquals(0, streakOf(u));
        assertTrue(lastNegUnset(u));
    }

    @Test
    void positiveStreak_twoLapses_zeroThenMinusOne() {
        // two closed uncompleted daily windows: 3 -> 0 -> -1
        run(habit(1, 3, false, TODAY.minusDays(2)));
        assertEquals(-1, streakOf(captureUpdate()));
    }

    @Test
    void completionFromNegative_resetsToOne_recordsLastNeg() {
        // Only yesterday's occurrence is completed; today's window is still open and untouched.
        LocalDate y = TODAY.minusDays(1);
        when(habitStructureRepository.existsByHabitIdAndCompletedInWindow(
                eq(1), eq(Boolean.TRUE), eq(y), eq(y))).thenReturn(true);
        run(habit(1, -5, false, y));
        Update u = captureUpdate();
        assertEquals(1, streakOf(u));
        assertEquals(-5, lastNegOf(u));
    }

    // --- long period grace window ---

    @Test
    void longPeriod_insideWindow_doesNotDock() {
        // 60-day habit, 30 days into the window, nothing done -> still 0, curDate unchanged
        LocalDate anchor = TODAY.minusDays(30);
        run(habit(60, 0, false, anchor));
        Update u = captureUpdate();
        assertEquals(0, streakOf(u));
        assertEquals(anchor, curDateOf(u));
    }

    @Test
    void longPeriod_completedMidWindow_creditsAndAdvancesOnePeriod() {
        when(habitStructureRepository.existsByHabitIdAndCompletedInWindow(
                eq(1), eq(Boolean.TRUE), any(), any())).thenReturn(true);
        LocalDate anchor = TODAY.minusDays(30);
        run(habit(60, 0, false, anchor));
        Update u = captureUpdate();
        assertEquals(1, streakOf(u));
        assertEquals(anchor.plusDays(60), curDateOf(u));
    }

    @Test
    void longPeriod_multipleWindowsLapsedDuringDowntime_docksOncePerWindow() {
        // 60-day habit untouched across 3 fully-elapsed windows -> -3
        run(habit(60, 0, false, TODAY.minusDays(190)));
        assertEquals(-3, streakOf(captureUpdate()));
    }

    // --- defaultMade (assumed done unless relapse) ---

    @Test
    void defaultMade_noRelapse_incrementsAtWindowClose() {
        // no relapse in yesterday's closed window -> success -> 0 becomes 1
        run(habit(1, 0, true, TODAY.minusDays(1)));
        assertEquals(1, streakOf(captureUpdate()));
    }

    @Test
    void defaultMade_relapse_docksToZeroFromPositive() {
        when(habitStructureRepository.existsByHabitIdAndCompletedInWindow(
                eq(1), eq(Boolean.FALSE), any(), any())).thenReturn(true); // relapse present
        run(habit(1, 3, true, TODAY.minusDays(1)));
        assertEquals(0, streakOf(captureUpdate()));
    }

    @Test
    void defaultMade_currentOpenWindow_notResolvedEarly() {
        // curDate = today: window still open, must NOT advance/credit (would hide it from Today)
        run(habit(1, 5, true, TODAY));
        Update u = captureUpdate();
        assertEquals(5, streakOf(u));
        assertEquals(TODAY, curDateOf(u));
    }

    // --- guards ---

    @Test
    void inactiveHabit_isSkipped() {
        Habit inactive = habit(1, 0, false, TODAY.minusDays(1));
        inactive.setActive(false);
        run(inactive);
        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(Habit.class));
    }

    @Test
    void futureHabit_notYetStarted_isNotDocked() {
        LocalDate anchor = TODAY.plusDays(5);
        run(habit(1, 0, false, anchor));
        Update u = captureUpdate();
        assertEquals(0, streakOf(u));
        assertEquals(anchor, curDateOf(u));
    }
}
