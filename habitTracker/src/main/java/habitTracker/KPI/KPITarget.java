package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// Embedded in KPI (goalType TARGET). baseline = latest reading when the target was set; null until
// the first reading arrives, then filled by KPIService.saveKPIDataPoint. setAt anchors the expected pace.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KPITarget {
    private Double value;
    private LocalDate deadline;
    private Double baseline;
    private LocalDate setAt;

    // Inferred direction: true if the target is above the baseline. Null while baseline is unknown or equal.
    public Boolean inferredHigherIsBetter() {
        if (value == null || baseline == null || value.equals(baseline)) return null;
        return value > baseline;
    }
}
