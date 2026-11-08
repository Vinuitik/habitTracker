package habitTracker.KPI.deploy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

/** One device's raw value for one local day (docs/designs/kpi-multidevice.md). Unique (kpiId, deviceId, date); newest recordedAt wins. */
@Document(collection = "kpi_device_readings")
@CompoundIndex(name = "kpi_device_date", def = "{'kpiId': 1, 'deviceId': 1, 'date': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiDeviceReading {
    @Id
    private String id;
    private String userId;
    private String kpiId;
    private String deviceId;
    private Integer capabilityVersion;
    private LocalDate date;
    private double value;
    private String unit;
    /** ISO timestamp the runner recorded the reading. */
    private String recordedAt;
}
