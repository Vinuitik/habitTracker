package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDate;

// No @Document annotation since collection name is dynamic
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPIData {
    @Id
    private String id;
    
    @Indexed
    private LocalDate date;
    
    private Double value;
    
    private Double exponentialMovingAverage; // calculated EMA
}
