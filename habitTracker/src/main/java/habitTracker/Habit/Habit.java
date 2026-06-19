package habitTracker.Habit;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
    // Validation group: constraints that must hold only when creating a brand-new habit.
    // Partial-update endpoints (edit, info/save) validate the Default group only, so a null
    // field means "leave unchanged" instead of failing NotBlank/NotNull.
    public interface OnCreate {}

    @Id
    private Integer id = new ObjectId().hashCode();

    @NotBlank(groups = OnCreate.class, message = "Habit name is required")
    @Size(max = 100, message = "Habit name must be 100 characters or fewer")
    private String name;

    @NotNull(groups = OnCreate.class, message = "Frequency is required")
    @Positive(message = "Frequency must be a positive number of days")
    private Integer frequency;
    private LocalDate startDate;
    private LocalDate curDate;
    private LocalDate endDate;
    private Boolean active;
    private Integer streak; // implement streaks
    private Integer longestStreak; // implement longest streaks
    @Size(max = 2000, message = "Description must be 2000 characters or fewer")
    private String description;
    @Pattern(regexp = "priority|maintaining|abandoned", message = "Status must be priority, maintaining, or abandoned")
    private String status; // prioroty, maintaining, abandoned.
    @Size(max = 2000, message = "2-minute rule must be 2000 characters or fewer")
    private String twoMinuteRule; // 2 minute rule
    private Boolean defaultMade;
    private Integer lastNegativeStreak;

    @Indexed
    private String userId;
}
