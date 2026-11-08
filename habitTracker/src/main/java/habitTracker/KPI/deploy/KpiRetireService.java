package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.KPI.CapabilityStatus;
import habitTracker.KPI.CollectorBundle;
import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.ProxyType;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * "Stop automating": the KPI keeps its data and goes back to manual entry. Capability versions → RETIRED, every
 * deployment row → target REMOVED (KpiDeploymentReconciler deletes the collectors). Also reports which programs the
 * KPI's manifest variants required on each device so the UI can offer to uninstall them (PackageUninstallService).
 */
@Service
public class KpiRetireService {

    public record DeviceRef(String deviceId, String deviceName) {}

    /** One program, with the devices that got it for this KPI. name = plain-language ("ActivityWatch"). */
    public record RequiredPackage(String manager, String id, String name, String why, List<DeviceRef> devices) {}

    public record Retired(String kpiId, String kpiName, List<RequiredPackage> packages) {}

    private static final Set<CapabilityStatus> ALREADY_DEAD =
            EnumSet.of(CapabilityStatus.REJECTED, CapabilityStatus.DISABLED, CapabilityStatus.RETIRED);

    private final KPIRepository kpis;
    private final KPIProxyCapabilityRepository capabilities;
    private final KpiDeploymentRepository deployments;
    private final DeviceService devices;
    private final KpiDeploymentReconciler reconciler;

    public KpiRetireService(KPIRepository kpis, KPIProxyCapabilityRepository capabilities,
                            KpiDeploymentRepository deployments, DeviceService devices, KpiDeploymentReconciler reconciler) {
        this.kpis = kpis;
        this.capabilities = capabilities;
        this.deployments = deployments;
        this.devices = devices;
        this.reconciler = reconciler;
    }

    /** Empty when the KPI is not this user's. Idempotent: a second call reports no packages. */
    public Optional<Retired> retire(String userId, String kpiId) {
        KPI kpi = userId == null ? null : kpis.findById(kpiId).filter(k -> userId.equals(k.getUserId())).orElse(null);
        if (kpi == null) return Optional.empty();

        List<KpiDeployment> rows = deployments.findByKpiId(kpiId).stream().filter(d -> userId.equals(d.getUserId())).toList();
        Map<String, KPIProxyCapability> caps = new HashMap<>();
        for (KPIProxyCapability c : capabilities.findByKpiIdAndUserId(kpiId, userId)) caps.put(c.getId(), c);

        List<RequiredPackage> packages = requiredPackages(userId, rows, caps);

        for (KPIProxyCapability c : caps.values()) {
            if (c.getStatus() != null && ALREADY_DEAD.contains(c.getStatus())) continue;
            c.setStatus(CapabilityStatus.RETIRED);
            capabilities.save(c);
        }
        Set<String> kicked = new LinkedHashSet<>();
        for (KpiDeployment d : rows) {
            if (d.getStatus() == KpiDeploymentStatus.REMOVED && d.getTarget() == KpiDeploymentTarget.REMOVED) continue;
            d.setTarget(KpiDeploymentTarget.REMOVED);
            if (d.getStatus() != KpiDeploymentStatus.REMOVED) d.setStatus(KpiDeploymentStatus.REMOVING);
            d.setAttempts(0);
            d.setNextAttemptAt(null);
            d.setLastError(null);
            d.setUpdatedAt(Instant.now());
            deployments.save(d);
            kicked.add(d.getDeviceId());
        }
        if (kpi.getProxyType() == ProxyType.RUNNER_COLLECTOR) {
            kpi.setProxyType(ProxyType.NONE);
            kpi.setUpdatedAt(LocalDateTime.now());
            kpis.save(kpi);
        }
        kicked.forEach(reconciler::kick);
        return Optional.of(new Retired(kpiId, kpi.getName(), packages));
    }

    /** Programs the still-wanted rows' manifests require, one entry per program, with the devices that have it. */
    List<RequiredPackage> requiredPackages(String userId, List<KpiDeployment> rows, Map<String, KPIProxyCapability> caps) {
        Map<String, RequiredPackage> byKey = new LinkedHashMap<>();
        for (KpiDeployment d : rows) {
            if (d.getTarget() != KpiDeploymentTarget.ACTIVE || d.getStatus() == KpiDeploymentStatus.REMOVED) continue;
            KPIProxyCapability cap = caps.get(d.getCapabilityId());
            CollectorBundle bundle = cap == null ? null : CollectorBundle.fromJson(cap.getCollectorBundle());
            if (bundle == null) continue;
            JsonNode requires;
            try {
                requires = bundle.resolve(d.getOs()).path("requires");
            } catch (IllegalArgumentException e) {
                continue; // no variant for this device's OS: nothing was installed for it
            }
            if (!requires.isArray()) continue;
            String deviceName = devices.owned(userId, d.getDeviceId()).map(Device::getName).orElse(d.getDeviceId());
            for (JsonNode r : requires) {
                String manager = r.path("manager").asText("");
                String id = r.path("id").asText("");
                if (manager.isBlank() || id.isBlank()) continue;
                RequiredPackage p = byKey.computeIfAbsent(manager + "\u0000" + id, k -> new RequiredPackage(manager, id,
                        PackageNames.friendly(manager, id), r.path("why").asText(""), new ArrayList<>()));
                if (p.devices().stream().noneMatch(x -> x.deviceId().equals(d.getDeviceId()))) {
                    p.devices().add(new DeviceRef(d.getDeviceId(), deviceName));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
