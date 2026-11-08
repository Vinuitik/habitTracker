package habitTracker.KPI.deploy;

import habitTracker.KPI.KPIRepository;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import habitTracker.relay.UplinkRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Per-device rollout state of one KPI's collector (read by the KPI UI; retry for parked FAILED rows). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpi/{kpiId}/deployments")
public class KpiDeploymentController {

    public record DeploymentDTO(String id, String deviceId, String deviceName, String os, Integer capabilityVersion,
                                KpiDeploymentTarget target, KpiDeploymentStatus status, String lastError,
                                int attempts, Instant nextAttemptAt, boolean online) {}

    private final KpiDeploymentRepository deployments;
    private final KPIRepository kpis;
    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final KpiDeploymentReconciler reconciler;

    @GetMapping
    public ResponseEntity<List<DeploymentDTO>> list(@PathVariable String kpiId) {
        String user = SecurityUtils.getCurrentUserId();
        if (!owns(user, kpiId)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(deployments.findByKpiId(kpiId).stream()
                .filter(d -> user.equals(d.getUserId()))
                .sorted(Comparator.comparing(KpiDeployment::getDeviceId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(d -> d.getCapabilityVersion() == null ? 0 : d.getCapabilityVersion()))
                .map(d -> dto(user, d)).toList());
    }

    /** Resets attempts and lets the reconciler try again right away. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<DeploymentDTO> retry(@PathVariable String kpiId, @PathVariable String id) {
        String user = SecurityUtils.getCurrentUserId();
        if (!owns(user, kpiId)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        KpiDeployment dep = deployments.findById(id)
                .filter(d -> kpiId.equals(d.getKpiId()) && user.equals(d.getUserId())).orElse(null);
        if (dep == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        dep.setAttempts(0);
        dep.setLastError(null);
        dep.setNextAttemptAt(Instant.now());
        if (dep.getStatus() == KpiDeploymentStatus.FAILED) {
            dep.setStatus(dep.getTarget() == KpiDeploymentTarget.REMOVED ? KpiDeploymentStatus.REMOVING : KpiDeploymentStatus.PENDING);
        }
        dep.setUpdatedAt(Instant.now());
        deployments.save(dep);
        reconciler.kick(dep.getDeviceId());
        return ResponseEntity.ok(dto(user, dep));
    }

    private boolean owns(String user, String kpiId) {
        return user != null && kpis.findById(kpiId).filter(k -> user.equals(k.getUserId())).isPresent();
    }

    private DeploymentDTO dto(String user, KpiDeployment d) {
        String name = devices.owned(user, d.getDeviceId()).map(Device::getName).orElse(null);
        return new DeploymentDTO(d.getId(), d.getDeviceId(), name, d.getOs(), d.getCapabilityVersion(), d.getTarget(),
                d.getStatus(), d.getLastError(), d.getAttempts(), d.getNextAttemptAt(), uplinks.isOnline(d.getDeviceId()));
    }
}
