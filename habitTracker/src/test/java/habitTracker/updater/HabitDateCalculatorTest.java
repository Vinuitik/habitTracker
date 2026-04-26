package habitTracker.updater;

import habitTracker.Habit.Habit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class HabitDateCalculatorTest {

    private final HabitDateCalculator calc = new HabitDateCalculator();

    private Habit habit(int frequency, LocalDate startDate, LocalDate endDate) {
        Habit h = new Habit();
        h.setFrequency(frequency);
        h.setStartDate(startDate);
        h.setEndDate(endDate);
        return h;
    }

    @Test
    void frequency1_nullStartDate_alwaysTracked() {
        Habit h = habit(1, null, null);
        assertTrue(calc.shouldTrackHabitOnDate(h, LocalDate.now()));
        assertTrue(calc.shouldTrackHabitOnDate(h, LocalDate.now().minusDays(100)));
    }

    @Test
    void frequency1_afterStartDate_tracked() {
        Habit h = habit(1, LocalDate.of(2026, 1, 1), null);
        assertTrue(calc.shouldTrackHabitOnDate(h, LocalDate.of(2026, 6, 1)));
    }

    @Test
    void frequency1_beforeStartDate_notTracked() {
        Habit h = habit(1, LocalDate.of(2026, 6, 1), null);
        assertFalse(calc.shouldTrackHabitOnDate(h, LocalDate.of(2026, 5, 31)));
    }

    @Test
    void frequency1_onEndDate_tracked() {
        Habit h = habit(1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertTrue(calc.shouldTrackHabitOnDate(h, LocalDate.of(2026, 3, 31)));
    }

    @Test
    void frequency1_afterEndDate_notTracked() {
        Habit h = habit(1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertFalse(calc.shouldTrackHabitOnDate(h, LocalDate.of(2026, 4, 1)));
    }

    @Test
    void frequency7_onStartDate_tracked() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertTrue(calc.shouldTrackHabitOnDate(habit(7, start, null), start));
    }

    @Test
    void frequency7_onScheduledDays_tracked() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Habit h = habit(7, start, null);
        assertTrue(calc.shouldTrackHabitOnDate(h, start.plusDays(7)));
        assertTrue(calc.shouldTrackHabitOnDate(h, start.plusDays(14)));
        assertTrue(calc.shouldTrackHabitOnDate(h, start.plusDays(21)));
    }

    @Test
    void frequency7_betweenScheduledDays_notTracked() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Habit h = habit(7, start, null);
        assertFalse(calc.shouldTrackHabitOnDate(h, start.plusDays(1)));
        assertFalse(calc.shouldTrackHabitOnDate(h, start.plusDays(6)));
        assertFalse(calc.shouldTrackHabitOnDate(h, start.plusDays(8)));
    }
}
