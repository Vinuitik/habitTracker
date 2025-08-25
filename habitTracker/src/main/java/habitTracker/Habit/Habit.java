package habitTracker.Habit;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;

@Document(collection = "habits")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class Habit {
    @Id
    private Integer id = new ObjectId().hashCode();

    private String name;

    private Integer frequency;
    private LocalDate startDate;
    private LocalDate curDate;
    private LocalDate endDate;
    private Boolean active;
    private Integer streak; // implement streaks
    private Integer longestStreak; // implement longest streaks
    private String description;
    private String status; // prioroty, maintaining, abandoned.
    private String twoMinuteRule; // 2 minute rule
    private Boolean defaultMade;
}
