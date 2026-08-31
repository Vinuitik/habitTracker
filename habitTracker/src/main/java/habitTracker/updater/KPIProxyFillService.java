package habitTracker.updater;

import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIDataSource;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.KPIService;
import habitTracker.KPI.ProxyHealthService;
import habitTracker.KPI.ProxyProvider;
import habitTracker.KPI.ProxyProviderRegistry;
import habitTracker.KPI.ProxyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Fills in yesterday's value for any KPI wired to an automatic proxy source (KPI.proxyType is
 * something other than NONE/MANUAL_PROMPT) by resolving that type's ProxyProvider from the
 * registry and, if it returns a value, writing it via KPIService.addKPIDataForUser(..., source).
 *
 * NONE means "no proxy configured" (the default — untouched, zero behavior change for every
 * pre-M1 KPI). MANUAL_PROMPT means "ask the user," not "fetch automatically," so it is
 * deliberately excluded here too even though it's a non-NONE proxyType.
 *
 * M12: a KPI whose proxyStatus is NEEDS_REPAIR (ProxyHealthService's circuit breaker has tripped)
 * is skipped entirely — no provider call, no candidate count — until a human resets it. Every
 * fetch is guarded against the provider throwing, since one flaky proxy must not take down the
 * rest of the nightly pass; a thrown exception is treated exactly like Optional.empty() for
 * ProxyHealthService.recordFailure purposes. A successful-but-anomalous value (ProxyHealthService.
 * isAnomalous) is still written — it may be real data — but also trips the breaker so the proxy
 * isn't hit again until reviewed.
 *
 * Mirrors KPIDefaultFillService's scan-all-users-then-filter pattern (no request context on a
 * cron thread); every write is scoped to that KPI's own id-keyed collection via KPIService, so
 * one user's proxy data never touches another user's.
 */
@Service
public class KPIProxyFillService {

    private static final Logger log = LoggerFactory.getLogger(KPIProxyFillService.class);

    private final KPIRepository kpiRepository;
    private final KPIService kpiService;
    private final ProxyProviderRegistry registry;
    private final ProxyHealthService proxyHealthService;

    public KPIProxyFillService(KPIRepository kpiRepository, KPIService kpiService, ProxyProviderRegistry registry,
                                ProxyHealthService proxyHealthService) {
        this.kpiRepository = kpiRepository;
        this.kpiService = kpiService;
        this.registry = registry;
        this.proxyHealthService = proxyHealthService;
    }

    public void fillFromProxies() {
        LocalDate targetDate = LocalDate.now().minusDays(1);
        List<KPI> active = kpiRepository.findByActive(true);

        int candidates = 0;
        int filled = 0;
        int skippedNeedsRepair = 0;
        for (KPI kpi : active) {
            ProxyType type = kpi.getProxyType() != null ? kpi.getProxyType() : ProxyType.NONE;
            if (type == ProxyType.NONE || type == ProxyType.MANUAL_PROMPT) {
                continue; // not wired to an automatic proxy — untouched by this step
            }
            if (proxyHealthService.needsRepair(kpi)) {
                skippedNeedsRepair++;
                continue; // circuit breaker tripped — don't hammer a known-broken proxy every night
            }
            candidates++;

            Optional<ProxyProvider> provider = registry.resolve(type);
            if (provider.isEmpty()) {
                continue; // configured for a type with no registered provider bean
            }

            Optional<Double> value;
            try {
                value = provider.get().fetchValue(kpi, targetDate);
            } catch (Exception e) {
                log.warn("Proxy fetch threw for KPI id={} name={} proxyType={}: {}",
                        kpi.getId(), kpi.getName(), type, e.getMessage());
                value = Optional.empty();
            }

            if (value.isEmpty()) {
                proxyHealthService.recordFailure(kpi);
                continue;
            }

            boolean anomalous = proxyHealthService.isAnomalous(kpi, value.get());
            kpiService.addKPIDataForUser(kpi.getUserId(), kpi.getName(), targetDate, value.get(), sourceFor(type));
            filled++;

            if (anomalous) {
                proxyHealthService.flagAnomaly(kpi);
            } else {
                proxyHealthService.recordSuccess(kpi);
            }
        }
        System.out.println("KPI proxy-fill: " + filled + "/" + candidates + " KPI(s) filled for " + targetDate
                + " (" + skippedNeedsRepair + " skipped, NEEDS_REPAIR)");
    }

    private KPIDataSource sourceFor(ProxyType type) {
        return type == ProxyType.TRELLO_CARD_COUNT ? KPIDataSource.PROXY_TRELLO : KPIDataSource.PROXY_CAPABILITY;
    }
}
