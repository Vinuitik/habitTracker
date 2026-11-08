package habitTracker.KPI.deploy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One KPI collector version on one device (docs/designs/kpi-multidevice.md "KpiDeployment"). Holds the per-device
 * state that used to live on KPIProxyCapability: collectorId, readings cursor, activation. The hub stores what
 * SHOULD be on the device (target); a reconciler converges status when the runner is online.
 */
@Document(collection = "kpi_deployments")
@CompoundIndex(name = "kpi_device_version", def = "{'kpiId': 1, 'deviceId': 1, 'capabilityVersion': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiDeployment {
    @Id
    private String id;
    private String userId;
    private String kpiId;
    private String capabilityId;
    private Integer capabilityVersion;
    private String deviceId;
    /** Go runtime.GOOS of the device (windows|linux|darwin); null until known. */
    private String os;

    @Builder.Default
    private KpiDeploymentTarget target = KpiDeploymentTarget.ACTIVE;
    @Builder.Default
    private KpiDeploymentStatus status = KpiDeploymentStatus.PENDING;

    /** Id on the runner ("kpi-<kpiId>", same across versions). */
    private String collectorId;
    /** The runner's sha256 of this device's resolved manifest. */
    private String contractHash;
    private boolean approved;
    /** POST /activate succeeded. */
    private boolean activated;
    /** Max recordedAt seen from this device (ISO, the runner's ?since=). */
    private String readingsSince;
    private LocalDate lastPulledOn;

    private int attempts;
    private Instant nextAttemptAt;
    /** One plain sentence. */
    private String lastError;

    private Instant createdAt;
    private Instant updatedAt;
}
