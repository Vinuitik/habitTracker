package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.KPI.CollectorBundle;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.UplinkRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/** Read-only data for the Automate dialog: which devices can run the KPI, and what the built plan would run/install where. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpi/automate")
public class KpiAutomateDevicesController {

    public record DeviceChip(String deviceId, String name, String os, boolean online) {}
    public record Install(String manager, String id, String why) {}
    public record Variant(String os, List<String> sources, List<Install> installs) {}
    public record Plan(List<DeviceChip> runsOn, List<Variant> variants) {}

    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final RunnerCollectorClient runner;
    private final KPIRepository kpis;
    private final KPIProxyCapabilityRepository capabilities;

    /** The owner's paired devices; os (Go runtime.GOOS) only known for online ones. */
    @GetMapping("/devices")
    public ResponseEntity<List<DeviceChip>> list() {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(devices.list(user).stream().map(d -> chip(user, d)).toList());
    }

    /** The newest plan of a KPI: where it runs (its chosen devices) and, per OS variant, what it installs. */
    @GetMapping("/plan")
    public ResponseEntity<Plan> plan(@RequestParam String kpiName) {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var kpi = kpis.findByNameAndUserId(kpiName, user).orElse(null);
        if (kpi == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        KPIProxyCapability cap = capabilities.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), user).orElse(null);
        if (cap == null) return ResponseEntity.noContent().build();

        List<DeviceChip> runsOn = new ArrayList<>();
        if (cap.getTargetDeviceIds() != null) {
            for (String id : cap.getTargetDeviceIds()) devices.owned(user, id).ifPresent(d -> runsOn.add(chip(user, d)));
        }
        List<Variant> variants = new ArrayList<>();
        CollectorBundle bundle = CollectorBundle.fromJson(cap.getCollectorBundle());
        if (bundle != null) {
            if (bundle.isLegacy()) {
                variants.add(variant(null, bundle.manifest()));
            } else {
                bundle.variants().forEach((os, v) -> variants.add(variant(os, v)));
            }
        }
        return ResponseEntity.ok(new Plan(runsOn, variants));
    }

    private static Variant variant(String os, JsonNode v) {
        List<String> sources = new ArrayList<>();
        JsonNode src = v.get("sources");
        if (src != null && src.isArray()) src.forEach(s -> sources.add(s.path("type").asText("")));
        List<Install> installs = new ArrayList<>();
        JsonNode req = v.get("requires");
        if (req != null && req.isArray()) {
            req.forEach(r -> installs.add(new Install(r.path("manager").asText(""), r.path("id").asText(""), r.path("why").asText(""))));
        }
        return new Variant(os, sources, installs);
    }

    private DeviceChip chip(String user, Device d) {
        boolean online = uplinks.isOnline(d.getId());
        String os = null;
        if (online) {
            try {
                os = runner.info(user, d.getId()).os();
            } catch (RuntimeException e) {
                os = null;   // went offline / old runner: the chip just shows no OS
            }
        }
        return new DeviceChip(d.getId(), d.getName(), os, online);
    }
}
