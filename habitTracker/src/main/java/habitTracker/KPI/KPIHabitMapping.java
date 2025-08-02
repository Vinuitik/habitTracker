package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

@Document(collection = "kpi_habit_mappings")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPIHabitMapping {
    @Id
    private String id;
    
    @Indexed
    private String kpiName; // reference to KPI by name
    
    @Indexed
    private Integer habitId; // reference to Habit by id
}
