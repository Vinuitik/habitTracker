package updater.services;

import org.springframework.stereotype.Service;
import updater.models.Habit;

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

    /**
     * Determines if a habit should be tracked on a specific date based on its frequency
     */
    public boolean shouldTrackHabitOnDate(Habit habit, LocalDate date) {
        LocalDate startDate = habit.getStartDate();
        int frequency = habit.getFrequency();
        
        // If the date is before the habit start date, don't track
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        
        // If the end date is set and the date is after it, don't track
        LocalDate endDate = habit.getEndDate();
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        
        // For daily habits (frequency=1), always track
        if (frequency == 1) {
            return true;
        }
        
        // For other frequencies, check if this is a day the habit should be tracked
        long daysSinceStart = ChronoUnit.DAYS.between(startDate, date);
        return daysSinceStart % frequency == 0;
    }
}