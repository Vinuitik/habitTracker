package habitTracker.KPI.deploy;

import habitTracker.auth.SecurityUtils;
import habitTracker.relay.DeviceService;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Retire ("Stop automating") + uninstall of the programs KPIs installed. All owner-checked; errors are plain
 * sentences in {"error": ...}.
 *   POST /api/kpi/{kpiId}/retire                      → {kpiId, kpiName, packages:[{manager,id,name,why,devices:[{deviceId,deviceName}]}]}
 *   POST /api/kpi/{kpiId}/retire/uninstall {packages:[{manager,id,deviceIds}]} → {results:[{deviceName,name,status,message,...}]}
 *   GET  /relay/api/devices/{deviceId}/kpi-packages   → [PackageRow]   ("Installed by KPIs" on the Relay device page)
 *   POST /relay/api/devices/{deviceId}/kpi-packages/uninstall {manager,id} → Result
 */
@RestController
@RequiredArgsConstructor
public class KpiPackageController {

    public record UninstallBody(List<PackageUninstallService.Target> packages) {}

    public record OneUninstallBody(String manager, String id) {}

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@+._/=*!-]{0,127}");
    private static final Pattern MANAGER = Pattern.compile("[a-z]{1,16}");

    private final KpiRetireService retireService;
    private final PackageUninstallService uninstall;
    private final DeviceService devices;
    private final habitTracker.KPI.KPIRepository kpis;

    @PostMapping("/api/kpi/{kpiId}/retire")
    public ResponseEntity<?> retire(@PathVariable String kpiId) {
        String user = SecurityUtils.getCurrentUserId();
        return retireService.retire(user, kpiId).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::kpiNotFound);
    }

    @PostMapping("/api/kpi/{kpiId}/retire/uninstall")
    public ResponseEntity<?> uninstallAfterRetire(@PathVariable String kpiId, @RequestBody UninstallBody body) {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null || kpis.findById(kpiId).filter(k -> user.equals(k.getUserId())).isEmpty()) return kpiNotFound();
        if (body == null || body.packages() == null || body.packages().isEmpty()) return bad("Nothing to uninstall.");
        for (PackageUninstallService.Target t : body.packages()) {
            if (!valid(t.manager(), t.id()) || t.deviceIds() == null) return bad("That program name is not valid.");
        }
        return ResponseEntity.ok(Map.of("results", uninstall.request(user, kpiId, body.packages())));
    }

    @GetMapping("/relay/api/devices/{deviceId}/kpi-packages")
    public ResponseEntity<?> list(@PathVariable String deviceId) {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null || devices.owned(user, deviceId).isEmpty()) return deviceNotFound();
        try {
            return ResponseEntity.ok(uninstall.list(user, deviceId));
        } catch (RunnerUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "This computer is offline right now."));
        } catch (RunnerCallException e) {
            String msg = e.getStatus() == 404 ? "This computer's runner is too old to list installed programs: update it."
                    : "The computer could not list its programs (" + e.getStatus() + ").";
            return ResponseEntity.status(e.getStatus() == 404 ? 404 : 502).body(Map.of("error", msg));
        }
    }

    @PostMapping("/relay/api/devices/{deviceId}/kpi-packages/uninstall")
    public ResponseEntity<?> uninstallOne(@PathVariable String deviceId, @RequestBody OneUninstallBody body) {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null || devices.owned(user, deviceId).isEmpty()) return deviceNotFound();
        if (body == null || !valid(body.manager(), body.id())) return bad("That program name is not valid.");
        return uninstall.uninstallNow(user, deviceId, body.manager(), body.id())
                .<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::deviceNotFound);
    }

    private static boolean valid(String manager, String id) {
        return manager != null && id != null && MANAGER.matcher(manager).matches() && SAFE.matcher(id).matches();
    }

    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<?> kpiNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "KPI not found"));
    }

    private ResponseEntity<?> deviceNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Device not found"));
    }
}
