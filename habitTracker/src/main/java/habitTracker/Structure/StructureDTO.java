package habitTracker.Structure;

import java.time.LocalDate;
import java.util.Map;

import habitTracker.util.Pair;
import lombok.Data;

@Data
public class StructureDTO {

    public StructureDTO(LocalDate date) {
        this.date = date;
    }

    public StructureDTO() {
    }

    private LocalDate date;

    private Map<Pair<String, Integer>, Boolean> habits; // Use custom Pair
    
    // Enhanced structure to track habit activity status
    private Map<Pair<String, Integer>, HabitStatus> habitStatuses;
    
    // Enum to represent different habit states
    public enum HabitStatus {
        ACTIVE_COMPLETED,    // Habit was active and completed
        ACTIVE_INCOMPLETE,   // Habit was active but not completed
        INACTIVE             // Habit was not active on this date
    }
}
