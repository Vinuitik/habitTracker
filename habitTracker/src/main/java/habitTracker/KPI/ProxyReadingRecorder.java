package habitTracker.KPI;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Stores one automatic reading of a capability-backed KPI through the S1 guards, shared by the nightly fill
 * (SANDBOX_CAPABILITY, habitTracker.updater.KPIProxyFillService) and the runner pull (RUNNER_COLLECTOR,
 * RunnerCollectorProvider):
 *   implausible (ProxyHealthService.plausibilityReason) → pending + flag IMPLAUSIBLE, breaker untouched
 *   capability on SHADOW trial                           → pending + flag SHADOW, breaker untouched
 *   else → KPIService.addKPIDataForUser; anomalous → flagAnomaly (breaker trips → repair), else recordSuccess
 */
@Component
public class ProxyReadingRecorder {

    private final KPIService kpiService;
    private final ProxyHealthService health;

    public ProxyReadingRecorder(KPIService kpiService, ProxyHealthService health) {
        this.kpiService = kpiService;
        this.health = health;
    }

    public void record(KPI kpi, LocalDate date, double value, ProxyProvider.Context ctx, KPIDataSource source) {
        if (ctx == null) ctx = ProxyProvider.Context.NONE;
        Optional<String> implausible = health.plausibilityReason(kpi, value, ctx.unit());
        if (implausible != null && implausible.isPresent()) {
            kpiService.addFlaggedProxyDataForUser(kpi.getUserId(), kpi.getName(), date, value, source,
                    "IMPLAUSIBLE", implausible.get());
            health.recordSuccess(kpi);
            return;
        }
        if (ctx.shadow()) {
            kpiService.addFlaggedProxyDataForUser(kpi.getUserId(), kpi.getName(), date, value, source,
                    "SHADOW", "Trial run: check this against what really happened");
            health.recordSuccess(kpi);
            return;
        }
        boolean anomalous = health.isAnomalous(kpi, value);
        kpiService.addKPIDataForUser(kpi.getUserId(), kpi.getName(), date, value, source);
        if (anomalous) health.flagAnomaly(kpi);
        else health.recordSuccess(kpi);
    }
}
