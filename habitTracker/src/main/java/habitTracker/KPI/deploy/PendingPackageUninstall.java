package habitTracker.KPI.deploy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * "Uninstall this program from that device once it can be done": the user chose Uninstall when retiring a KPI but the
 * device was offline, or its collector was still being removed. Sent by PackageUninstallService (on RunnerConnected,
 * after the KPI's collector is gone, or on its 30s sweep), then deleted. Own collection so deployment rows (per
 * version) stay free of it.
 */
@Document(collection = "pending_package_uninstalls")
@CompoundIndex(name = "pending_unique", def = "{'userId': 1, 'deviceId': 1, 'manager': 1, 'packageId': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingPackageUninstall {
    @Id
    private String id;
    private String userId;
    private String deviceId;
    private String manager;
    private String packageId;
    /** The retired KPI whose collector must be gone from the device before the runner will accept the uninstall. */
    private String kpiId;
    private Instant createdAt;
}
