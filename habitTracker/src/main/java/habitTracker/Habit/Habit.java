package habitTracker.Habit;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "habits")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class Habit {
    @Id
    private String name;

    private String frequency;
    private String startDate;
    private String endDate;
}
