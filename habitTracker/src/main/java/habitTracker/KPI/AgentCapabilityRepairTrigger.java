package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * M12 auto-repair: when ProxyHealthService trips a KPI to NEEDS_REPAIR and that KPI runs a sandbox
 * capability, kick off CapabilityAgentService.repairFor (new version seeded with the failure, same
 * test gate as M8, up to MAX_ATTEMPTS). Runs on a single background thread so the nightly job is
 * not blocked by LLM calls. Other proxy types (e.g. Trello) have no generated code to repair: logged only.
 * Failure is never fatal: the KPI simply stays NEEDS_REPAIR for the manual "fix it" button.
 *
 * Generation runs on the KPI owner's Relay runner. No runner online (before or mid-repair) → the KPI is
 * parked in an in-memory waiting set (kpiId → userId) and retryWaiting() re-dispatches it once a runner of
 * that user is online. Waiting is not a failure: no attempt is counted. The set is lost on restart.
 */
@Component
public class AgentCapabilityRepairTrigger implements CapabilityRepairTrigger {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityRepairTrigger.class);

    private final CapabilityAgentService service;
    private final CapabilityGenerator generator;
    private final KPIRepository kpiRepository;
    private final Executor executor;
    private final Map<String, String> waiting = new ConcurrentHashMap<>(); // kpiId → userId

    @Autowired
    public AgentCapabilityRepairTrigger(CapabilityAgentService service, CapabilityGenerator generator,
                                        KPIRepository kpiRepository) {
        this(service, generator, kpiRepository, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capability-repair");
            t.setDaemon(true);
            return t;
        }));
    }

    AgentCapabilityRepairTrigger(CapabilityAgentService service, CapabilityGenerator generator,
                                 KPIRepository kpiRepository, Executor executor) {
        this.service = service;
        this.generator = generator;
        this.kpiRepository = kpiRepository;
        this.executor = executor;
    }

    @Override
    public void attemptRepair(KPI kpi) {
        if (kpi.getProxyType() != ProxyType.SANDBOX_CAPABILITY) {
            log.info("KPI proxy repair: nothing to regenerate for KPI id={} proxyType={} (manual reset only)",
                    kpi.getId(), kpi.getProxyType());
            return;
        }
        if (!generator.runnerAvailable(kpi.getUserId())) {
            park(kpi.getId(), kpi.getUserId());
            return;
        }
        dispatch(kpi);
    }

    /** KPI ids whose repair is waiting for a runner of their owner to come online. */
    public Set<String> waitingKpiIds() {
        return Set.copyOf(waiting.keySet());
    }

    /** Re-dispatches parked repairs whose owner now has a runner online. Polling keeps relay free of KPI deps. */
    @Scheduled(fixedDelayString = "${kpi.repair.runner-check-ms:60000}",
               initialDelayString = "${kpi.repair.runner-check-ms:60000}")
    public void retryWaiting() {
        for (Map.Entry<String, String> e : Map.copyOf(waiting).entrySet()) {
            String kpiId = e.getKey();
            if (!generator.runnerAvailable(e.getValue())) continue;
            waiting.remove(kpiId);
            KPI kpi = kpiRepository.findById(kpiId).orElse(null);
            // Re-read: the user may have deleted the KPI, reset it manually, or fixed it via "fix it" meanwhile.
            if (kpi == null || kpi.getProxyType() != ProxyType.SANDBOX_CAPABILITY
                    || kpi.getProxyStatus() != ProxyStatus.NEEDS_REPAIR) {
                log.info("KPI proxy repair for KPI id={} no longer needed, dropped from waiting set", kpiId);
                continue;
            }
            log.info("KPI proxy repair for KPI id={}: runner online, resuming", kpiId);
            dispatch(kpi);
        }
    }

    private void dispatch(KPI kpi) {
        executor.execute(() -> {
            try {
                KPIProxyCapability result = service.repairFor(kpi.getUserId(), kpi, true);
                if (CapabilityAgentService.waitingForRunner(result)) {
                    park(kpi.getId(), kpi.getUserId()); // runner dropped mid-repair
                    return;
                }
                log.info("KPI proxy repair for KPI id={} finished: capability v{} status={}",
                        kpi.getId(), result.getVersion(), result.getStatus());
            } catch (Exception e) {
                log.warn("KPI proxy repair for KPI id={} did not run: {}", kpi.getId(), e.toString());
            }
        });
    }

    private void park(String kpiId, String userId) {
        waiting.put(kpiId, userId);
        log.info("KPI proxy repair for KPI id={} waiting for a runner of user {} to connect", kpiId, userId);
    }
}
