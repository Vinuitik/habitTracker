package habitTracker.updater;

import org.springframework.stereotype.Service;
import habitTracker.Habit.Habit;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class HabitDateCalculator {

    public LocalDate calculateNextOccurrence(Habit habit, LocalDate today) {
        LocalDate startDate = habit.getStartDate();
        int frequency = habit.getFrequency();

        int daysSinceStart = (int) ChronoUnit.DAYS.between(startDate, today);
        int offset = frequency - (daysSinceStart % frequency);

        if (offset == frequency) {
            return today;
        } else {
            return today.plusDays(offset);
        }
    }

    public boolean shouldTrackHabitOnDate(Habit habit, LocalDate date) {
        LocalDate startDate = habit.getStartDate();
        int frequency = habit.getFrequency() != null ? habit.getFrequency() : 1;

        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }

        LocalDate endDate = habit.getEndDate();
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }

        if (frequency == 1) {
            return true;
        }

        long daysSinceStart = ChronoUnit.DAYS.between(startDate, date);
        return daysSinceStart % frequency == 0;
    }
}
