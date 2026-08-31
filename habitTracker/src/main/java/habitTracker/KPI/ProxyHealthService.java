package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * M12 maintenance loop: tracks per-KPI proxy health (KPI.proxyStatus / consecutiveProxyFailures)
 * and decides when the nightly proxy-fill step (habitTracker.updater.KPIProxyFillService) should
 * trip the circuit breaker to NEEDS_REPAIR. Two independent triggers:
 *
 *  1. Consecutive failures: N straight Optional.empty()/exception results from a KPI's
 *     ProxyProvider.fetchValue (recordFailure). A success anywhere in the streak resets the
 *     counter to 0 (recordSuccess).
 *  2. Anomalous value: a *successfully* fetched value that lands wildly outside the KPI's own
 *     recent historical range (isAnomalous / flagAnomaly) — independent of the failure counter,
 *     since the fetch itself didn't fail.
 *
 * Either trigger flips proxyStatus to NEEDS_REPAIR and fires CapabilityRepairTrigger.attemptRepair
 * (currently a logging no-op — see LoggingCapabilityRepairTrigger). Manual reset back to ACTIVE is
 * KPIService.resetProxyHealth (PUT /api/kpis/{name}/proxy/reset), not this class.
 */
@Service
public class ProxyHealthService {

    private static final Logger log = LoggerFactory.getLogger(ProxyHealthService.class);

    // How many historical points to look at for the anomaly check, and the minimum required
    // before we trust the range enough to judge a new value against it — with only 1-2 points,
    // "outside the range" is meaningless (any 3rd value could legitimately widen the range).
    private static final int ANOMALY_HISTORY_WINDOW = 10;
    private static final int ANOMALY_MIN_HISTORY = 3;

    // A fetched value is anomalous if it falls more than (observed range * this factor) beyond
    // the historical min/max — i.e. it may exceed the recent range by up to 50% before being
    // flagged, tolerating normal drift while still catching wild outliers (a proxy returning 0,
    // a stuck sensor, a UI-scrape grabbing the wrong number). When the recent history has zero
    // variance (every point identical), there is no range to scale a margin from, so we fall back
    // to a margin proportional to the value's own magnitude, with an absolute floor for
    // near-zero histories.
    private static final double ANOMALY_MARGIN_FACTOR = 0.5;
    private static final double ANOMALY_FLAT_MARGIN_FACTOR = 0.5;
    private static final double ANOMALY_FLAT_MIN_MARGIN = 1.0;

    private final KPIRepository kpiRepository;
    private final DynamicKPIDataRepository dynamicKPIDataRepository;
    private final KPICollectionNameUtil collectionNameUtil;
    private final CapabilityRepairTrigger repairTrigger;
    private final int failureThreshold;

    public ProxyHealthService(KPIRepository kpiRepository,
                               DynamicKPIDataRepository dynamicKPIDataRepository,
                               KPICollectionNameUtil collectionNameUtil,
                               CapabilityRepairTrigger repairTrigger,
                               @Value("${kpi.proxy.failure-threshold:3}") int failureThreshold) {
        this.kpiRepository = kpiRepository;
        this.dynamicKPIDataRepository = dynamicKPIDataRepository;
        this.collectionNameUtil = collectionNameUtil;
        this.repairTrigger = repairTrigger;
        this.failureThreshold = failureThreshold;
    }

    /** True if the nightly proxy-fill step should skip this KPI's proxy entirely. */
    public boolean needsRepair(KPI kpi) {
        return kpi.getProxyStatus() == ProxyStatus.NEEDS_REPAIR;
    }

    /**
     * Checks a successfully-fetched value against this KPI's last ANOMALY_HISTORY_WINDOW
     * KPIData points. Returns false (not anomalous) when there isn't enough history yet to judge
     * — that is a "don't know" answer, not a "safe" one, deliberately not treated as a failure.
     */
    public boolean isAnomalous(KPI kpi, double value) {
        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());
        List<Double> values = dynamicKPIDataRepository.findTopNOrderByDateDesc(ANOMALY_HISTORY_WINDOW, collectionName)
                .stream()
                .map(KPIData::getValue)
                .filter(Objects::nonNull)
                .toList();

        if (values.size() < ANOMALY_MIN_HISTORY) {
            return false;
        }

        double min = Collections.min(values);
        double max = Collections.max(values);
        double range = max - min;
        double margin = range > 0
                ? range * ANOMALY_MARGIN_FACTOR
                : Math.max(Math.abs(min) * ANOMALY_FLAT_MARGIN_FACTOR, ANOMALY_FLAT_MIN_MARGIN);

        return value < min - margin || value > max + margin;
    }

    /** Record a fetch failure (empty/exception). Trips the breaker at failureThreshold. */
    public void recordFailure(KPI kpi) {
        int failures = (kpi.getConsecutiveProxyFailures() != null ? kpi.getConsecutiveProxyFailures() : 0) + 1;
        kpi.setConsecutiveProxyFailures(failures);
        if (failures >= failureThreshold) {
            kpi.setProxyStatus(ProxyStatus.NEEDS_REPAIR);
        }
        kpiRepository.save(kpi);

        if (kpi.getProxyStatus() == ProxyStatus.NEEDS_REPAIR) {
            log.warn("KPI proxy circuit breaker tripped (consecutive failures) for id={} name={} userId={}",
                    kpi.getId(), kpi.getName(), kpi.getUserId());
            repairTrigger.attemptRepair(kpi);
        }
    }

    /** Record a non-anomalous successful fetch. Resets the failure counter. */
    public void recordSuccess(KPI kpi) {
        kpi.setConsecutiveProxyFailures(0);
        kpiRepository.save(kpi);
    }

    /** Trip the breaker on an anomalous-but-successful value. Does not touch the failure counter. */
    public void flagAnomaly(KPI kpi) {
        kpi.setProxyStatus(ProxyStatus.NEEDS_REPAIR);
        kpiRepository.save(kpi);
        log.warn("KPI proxy circuit breaker tripped (anomalous value) for id={} name={} userId={}",
                kpi.getId(), kpi.getName(), kpi.getUserId());
        repairTrigger.attemptRepair(kpi);
    }
}
