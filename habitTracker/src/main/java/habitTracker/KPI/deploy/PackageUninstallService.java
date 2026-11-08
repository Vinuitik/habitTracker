package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.KPI.KPIRepository;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.RunnerConnected;
import habitTracker.relay.RunnerUnavailableException;
import habitTracker.relay.UplinkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uninstalling programs Relay put on a device (runner POST /v1/packages/uninstall) and the "Installed by KPIs" list.
 *
 * Retire flow: the user answers "Also uninstall X from Desktop, Laptop?" → request() stores one
 * PendingPackageUninstall per device and tries it at once. It is sent only after the retired KPI's collectors are gone
 * from that device (else the runner says 409 "in use" because of the very KPI being retired); if the device is
 * offline or still removing, it stays queued and is sent on RunnerConnected, or by a 30s sweep. Own listener so the
 * deployment reconciler stays untouched. Single node, in-process.
 *
 * Runner answers are turned into plain sentences: 409 → "Still needed by <kpi>", 400 → "Was already on this computer,
 * so Relay won't remove it".
 */
@Service
public class PackageUninstallService {

    private static final Logger log = LoggerFactory.getLogger(PackageUninstallService.class);
    static final String NOT_BY_RELAY = "Was already on this computer, so Relay won't remove it.";
    private static final Pattern COLLECTOR_KPI = Pattern.compile("^kpi-(.+?)(-v\\d+)?$");

    public enum Status { UNINSTALLING, QUEUED, STILL_NEEDED, NOT_BY_RELAY, FAILED }

    public record Result(String deviceId, String deviceName, String manager, String id, String name,
                         Status status, String message) {}

    /** A ledger row as shown on the device page. canUninstall=false → reason says why, in plain words. */
    public record PackageRow(String manager, String id, String name, String installedAt, boolean installedByRelay,
                             String state, String error, List<String> usedByKpis, boolean canUninstall, String reason) {}

    public record Target(String manager, String id, List<String> deviceIds) {}

    private final RunnerCollectorClient client;
    private final PendingPackageUninstallRepository pending;
    private final KpiDeploymentRepository deployments;
    private final KPIRepository kpis;
    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final Executor background;

    @Autowired
    public PackageUninstallService(RunnerCollectorClient client, PendingPackageUninstallRepository pending,
                                   KpiDeploymentRepository deployments, KPIRepository kpis, DeviceService devices,
                                   UplinkRegistry uplinks) {
        this(client, pending, deployments, kpis, devices, uplinks, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kpi-package-uninstall");
            t.setDaemon(true);
            return t;
        }));
    }

    PackageUninstallService(RunnerCollectorClient client, PendingPackageUninstallRepository pending,
                            KpiDeploymentRepository deployments, KPIRepository kpis, DeviceService devices,
                            UplinkRegistry uplinks, Executor background) {
        this.client = client;
        this.pending = pending;
        this.deployments = deployments;
        this.kpis = kpis;
        this.devices = devices;
        this.uplinks = uplinks;
        this.background = background;
    }

    // ── retire dialog ────────────────────────────────────────────────────────

    /** Queue + try now. Devices that are not the user's are skipped silently (never reveals them). */
    public List<Result> request(String userId, String kpiId, List<Target> targets) {
        List<Result> out = new ArrayList<>();
        for (Target t : targets) {
            for (String deviceId : t.deviceIds()) {
                Device dev = devices.owned(userId, deviceId).orElse(null);
                if (dev == null) continue;
                PendingPackageUninstall p = pending.findByUserIdAndDeviceIdAndManagerAndPackageId(userId, deviceId, t.manager(), t.id())
                        .orElseGet(() -> PendingPackageUninstall.builder().userId(userId).deviceId(deviceId)
                                .manager(t.manager()).packageId(t.id()).createdAt(Instant.now()).build());
                p.setKpiId(kpiId);
                pending.save(p);
                out.add(settle(p, dev.getName()));
            }
        }
        return out;
    }

    // ── queued sends ─────────────────────────────────────────────────────────

    @EventListener
    public void onRunnerConnected(RunnerConnected event) {
        background.execute(() -> processDevice(event.deviceId()));
    }

    /** Offline-then-removed and still-removing cases don't always have a connect event after them. */
    @Scheduled(fixedDelayString = "${kpi.package-uninstall.sweep-ms:30000}", initialDelayString = "${kpi.package-uninstall.sweep-ms:30000}")
    public void sweep() {
        try {
            pending.findAll().stream().map(PendingPackageUninstall::getDeviceId).distinct()
                    .filter(uplinks::isOnline).forEach(this::processDevice);
        } catch (RuntimeException e) {
            log.warn("[PackageUninstall] sweep failed: {}", e.toString());
        }
    }

    void processDevice(String deviceId) {
        for (PendingPackageUninstall p : pending.findByDeviceId(deviceId)) {
            try {
                String name = devices.owned(p.getUserId(), deviceId).map(Device::getName).orElse(deviceId);
                Result r = settle(p, name);
                if (r.status() == Status.QUEUED && uplinkGone(r)) break;
            } catch (RuntimeException e) {
                log.warn("[PackageUninstall] {} on {} failed: {}", p.getPackageId(), deviceId, e.toString());
            }
        }
    }

    private boolean uplinkGone(Result r) {
        return !uplinks.isOnline(r.deviceId());
    }

    /** One attempt. Deletes the queue entry unless the answer is "not yet" (offline / collector still being removed). */
    private synchronized Result settle(PendingPackageUninstall p, String deviceName) {
        String name = PackageNames.friendly(p.getManager(), p.getPackageId());
        if (pending.findById(p.getId()).isEmpty() && p.getId() != null) {
            return new Result(p.getDeviceId(), deviceName, p.getManager(), p.getPackageId(), name, Status.UNINSTALLING,
                    "Already being uninstalled.");
        }
        if (collectorStillThere(p)) {
            return new Result(p.getDeviceId(), deviceName, p.getManager(), p.getPackageId(), name, Status.QUEUED,
                    "Waiting for the KPI to be removed from " + deviceName + ", then Relay will uninstall " + name + ".");
        }
        Result r = attempt(p.getUserId(), p.getDeviceId(), deviceName, p.getManager(), p.getPackageId());
        if (r.status() != Status.QUEUED) pending.delete(p);
        return r;
    }

    private boolean collectorStillThere(PendingPackageUninstall p) {
        if (p.getKpiId() == null) return false;
        return deployments.findByKpiId(p.getKpiId()).stream()
                .filter(d -> p.getDeviceId().equals(d.getDeviceId()))
                .anyMatch(d -> d.getStatus() != KpiDeploymentStatus.REMOVED && d.getStatus() != KpiDeploymentStatus.FAILED);
    }

    // ── direct (device page) ─────────────────────────────────────────────────

    /** Empty when the device is not the user's. No queueing: the page only lists packages of an online device. */
    public Optional<Result> uninstallNow(String userId, String deviceId, String manager, String id) {
        Device dev = devices.owned(userId, deviceId).orElse(null);
        if (dev == null) return Optional.empty();
        Result r = attempt(userId, deviceId, dev.getName(), manager, id);
        if (r.status() == Status.QUEUED) {
            r = new Result(deviceId, dev.getName(), manager, id, r.name(), Status.FAILED, dev.getName() + " is offline right now. Try again when it is back.");
        }
        return Optional.of(r);
    }

    private Result attempt(String userId, String deviceId, String deviceName, String manager, String id) {
        String name = PackageNames.friendly(manager, id);
        try {
            client.uninstallPackage(userId, deviceId, manager, id);
            return new Result(deviceId, deviceName, manager, id, name, Status.UNINSTALLING,
                    "Uninstalling " + name + " from " + deviceName + ".");
        } catch (RunnerUnavailableException e) {
            return new Result(deviceId, deviceName, manager, id, name, Status.QUEUED,
                    deviceName + " is offline. Relay will uninstall " + name + " when it reconnects.");
        } catch (RunnerCallException e) {
            return switch (e.getStatus()) {
                case 400 -> new Result(deviceId, deviceName, manager, id, name, Status.NOT_BY_RELAY, NOT_BY_RELAY);
                case 409 -> new Result(deviceId, deviceName, manager, id, name, Status.STILL_NEEDED,
                        "Still needed by " + usedByText(userId, deviceId, manager, id) + ".");
                case 404 -> new Result(deviceId, deviceName, manager, id, name, Status.FAILED,
                        "This computer's runner is too old to uninstall programs: update it.");
                default -> new Result(deviceId, deviceName, manager, id, name, Status.FAILED,
                        "Could not uninstall " + name + ": " + runnerSentence(e));
            };
        }
    }

    // ── "Installed by KPIs" list ─────────────────────────────────────────────

    /** Throws RunnerUnavailableException / RunnerCallException for the controller to map. */
    public List<PackageRow> list(String userId, String deviceId) {
        JsonNode rows = client.packages(userId, deviceId);
        List<PackageRow> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) return out;
        for (JsonNode n : rows) {
            String manager = n.path("manager").asText("");
            String id = n.path("id").asText("");
            boolean byRelay = n.path("installedByRelay").asBoolean(false);
            String state = n.path("state").asText("installed");
            List<String> users = kpiNames(userId, n.path("usedBy"));
            String reason = null;
            if (!byRelay) reason = NOT_BY_RELAY;
            else if (!users.isEmpty()) reason = "Still needed by " + String.join(", ", users) + ".";
            else if (n.path("usedBy").size() > 0) reason = "Still needed by a KPI.";
            else if (!"installed".equals(state) && !"failed".equals(state)) {
                reason = "installing".equals(state) ? "Being installed right now." : "Waiting for you to confirm the install on that computer.";
            }
            out.add(new PackageRow(manager, id, PackageNames.friendly(manager, id), n.path("installedAt").asText(null), byRelay,
                    state, n.path("error").asText(null), users, reason == null, reason));
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** collector ids "kpi-<kpiId>[-vN]" → this user's KPI names, deduped; ids we can't resolve are skipped. */
    private List<String> kpiNames(String userId, JsonNode usedBy) {
        Set<String> names = new LinkedHashSet<>();
        if (usedBy != null && usedBy.isArray()) {
            for (JsonNode u : usedBy) {
                Matcher m = COLLECTOR_KPI.matcher(u.asText(""));
                if (!m.matches()) continue;
                kpis.findById(m.group(1)).filter(k -> userId.equals(k.getUserId())).ifPresent(k -> names.add(k.getName()));
            }
        }
        return new ArrayList<>(names);
    }

    private String usedByText(String userId, String deviceId, String manager, String id) {
        try {
            JsonNode rows = client.packages(userId, deviceId);
            if (rows != null && rows.isArray()) {
                for (JsonNode n : rows) {
                    if (manager.equals(n.path("manager").asText()) && id.equals(n.path("id").asText())) {
                        List<String> names = kpiNames(userId, n.path("usedBy"));
                        if (!names.isEmpty()) return String.join(", ", names);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic wording
        }
        return "another KPI";
    }

    private static String runnerSentence(RunnerCallException e) {
        try {
            String msg = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getBody()).path("error").asText("");
            if (!msg.isBlank()) return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
        } catch (Exception ignored) {
            // not JSON
        }
        return "the computer answered with an error (" + e.getStatus() + ").";
    }
}
