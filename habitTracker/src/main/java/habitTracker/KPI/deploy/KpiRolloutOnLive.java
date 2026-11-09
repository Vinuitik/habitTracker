package habitTracker.KPI.deploy;

import habitTracker.KPI.CapabilityPlatform;
import habitTracker.KPI.CapabilityWentLive;
import habitTracker.KPI.KPIProxyCapability;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * When a version goes live, make sure it has a deployment row on EVERY target device (the build only creates the
 * proposing device's row), so the reconciler rolls it out everywhere. The previous version's rows are untouched: the
 * reconciler removes them per device only after this version runs there.
 */
@Component
public class KpiRolloutOnLive {

    private final KpiDeploymentRepository deployments;
    private final KpiDeploymentReconciler reconciler;

    public KpiRolloutOnLive(KpiDeploymentRepository deployments, KpiDeploymentReconciler reconciler) {
        this.deployments = deployments;
        this.reconciler = reconciler;
    }

    @EventListener
    public void onLive(CapabilityWentLive event) {
        KPIProxyCapability cap = event.capability();
        if (cap.getPlatform() != CapabilityPlatform.RUNNER || cap.getTargetDeviceIds() == null) return;
        ensureRows(cap);
    }

    /** → ids of the devices that got a new row. Idempotent. */
    List<String> ensureRows(KPIProxyCapability cap) {
        List<String> created = new ArrayList<>();
        for (String device : cap.getTargetDeviceIds().stream().filter(d -> d != null && !d.isBlank()).distinct().toList()) {
            if (deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(cap.getKpiId(), device, cap.getVersion()).isPresent()) continue;
            deployments.save(KpiDeployment.builder()
                    .userId(cap.getUserId()).kpiId(cap.getKpiId()).capabilityId(cap.getId())
                    .capabilityVersion(cap.getVersion()).deviceId(device)
                    .collectorId("kpi-" + cap.getKpiId() + "-v" + cap.getVersion())
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build());
            created.add(device);
        }
        created.forEach(reconciler::kick);
        return created;
    }
}
