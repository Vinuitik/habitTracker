package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDate;

@Document(collection = "kpi_data")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPIData {
    @Id
    private String id;
    
    @Indexed
    private String kpiName; // reference to KPI by name
    
    @Indexed
    private LocalDate date;
    
    private Double value;
    
    private Double exponentialMovingAverage; // calculated EMA
}
