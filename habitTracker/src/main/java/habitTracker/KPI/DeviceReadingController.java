package habitTracker.KPI;

import habitTracker.KPI.deploy.KpiDeviceReadingRepository;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-device breakdown behind a combined KPI point (UI "which device gave what"). Owner-checked. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpi")
public class DeviceReadingController {

    public record DeviceReadingDTO(LocalDate date, String deviceId, String deviceName, double value, String unit) {}

    private final KPIRepository kpiRepository;
    private final KpiDeviceReadingRepository readings;
    private final DeviceRepository devices;

    @GetMapping("/{kpiId}/device-readings")
    public ResponseEntity<?> deviceReadings(@PathVariable String kpiId,
                                            @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        String userId = SecurityUtils.getCurrentUserId();
        KPI kpi = userId == null ? null : kpiRepository.findById(kpiId).orElse(null);
        if (kpi == null || !userId.equals(kpi.getUserId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "KPI not found"));
        }
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(30);
        Map<String, String> names = new HashMap<>();
        List<DeviceReadingDTO> out = readings.findByKpiIdAndDateBetweenOrderByDateAscDeviceIdAsc(kpiId, start, end).stream()
                .map(r -> new DeviceReadingDTO(r.getDate(), r.getDeviceId(),
                        names.computeIfAbsent(r.getDeviceId(), id -> devices.findByIdAndUserId(id, userId)
                                .map(Device::getName).orElse(id)),
                        r.getValue(), r.getUnit()))
                .toList();
        return ResponseEntity.ok(out);
    }
}
