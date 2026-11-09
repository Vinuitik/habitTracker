package habitTracker.KPI.deploy;

import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** The days a KPI's definition changed in a way that makes old and new readings not comparable (chart markers). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpi/{kpiId}/definition-changes")
public class KpiDefinitionChangeController {

    public record Change(LocalDate date, Integer version) {}

    private final KPIProxyCapabilityRepository capabilities;
    private final KPIRepository kpis;

    @GetMapping
    public ResponseEntity<List<Change>> list(@PathVariable String kpiId) {
        String user = SecurityUtils.getCurrentUserId();
        if (user == null || kpis.findById(kpiId).filter(k -> user.equals(k.getUserId())).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(capabilities.findByKpiIdAndUserId(kpiId, user).stream()
                .filter(c -> Boolean.TRUE.equals(c.getMeaningChanged()) && c.getLiveSince() != null)
                .sorted(Comparator.comparing(c -> c.getLiveSince()))
                .map(c -> new Change(c.getLiveSince(), c.getVersion())).toList());
    }
}
