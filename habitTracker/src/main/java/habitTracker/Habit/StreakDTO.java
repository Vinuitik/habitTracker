package habitTracker.Habit;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Streak payload for POST /habits/streaks. Keeps the historical {key, value} field names (habitId,
 * streak) so existing consumers keep working, and adds frequency so the client can scale the
 * negative-streak color by period (a longer period reddens faster).
 */
@Getter
@AllArgsConstructor
public class StreakDTO {
    private final Integer key;        // habitId
    private final Integer value;      // streak
    private final Integer frequency;  // days between occurrences
}
