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

    private Boolean autoFilled; // true if this point was synthesized by the default-fill cron, not entered manually

    // Where this value came from. Defaults to MANUAL via both the field initializer and
    // @Builder.Default, which matters for two distinct cases: (1) a pre-M1 Mongo document that
    // predates this field entirely — Spring Data's converter leaves properties absent from the
    // source document untouched at whatever the no-arg constructor set them to, so it reads back
    // as MANUAL, not null; (2) code building a KPIData via the Lombok builder without specifying
    // source (e.g. old call sites not yet updated).
    @Builder.Default
    private KPIDataSource source = KPIDataSource.MANUAL;

    // True if this point was written automatically (by a proxy provider) but still awaits manual
    // confirmation. Not yet set by anything in M1 — KPI.confirmSampleRate-driven sampling is a
    // later milestone — but the field exists now so that milestone doesn't need another schema
    // migration.
    @Builder.Default
    private Boolean pending = false;
}
