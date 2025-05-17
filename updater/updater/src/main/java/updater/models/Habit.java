package updater.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document(collection = "habits")
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Habit {
    @Id
    private Integer id;
    private String name;
    private Integer frequency;
    private LocalDate startDate;
    private LocalDate curDate;
    private LocalDate endDate;
    private Boolean active;
    private Integer streak;
    private Integer longestStreak; // keep track of maximum
    private String description;
    private String status; // prioroty, maintaining, abandoned.
    private String twoMinuteRule; // 2 minute rule
}