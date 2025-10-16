package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KPIDataDTO {
    private String id;
    private String kpiName;
    private LocalDate date;
    private Double value;
    private Double exponentialMovingAverage;
    private String trendDirection; // "up", "down", "stable"
    private String colorIntensity; // "low", "medium", "high" for color strength
    private Boolean higherIsBetter; // true if higher values are better, false otherwise
}
