package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * M12 auto-repair: when ProxyHealthService trips a KPI to NEEDS_REPAIR and that KPI runs a sandbox
 * capability, kick off CapabilityAgentService.repairFor (new version seeded with the failure, same
 * test gate as M8, up to MAX_ATTEMPTS). Runs on a single background thread so the nightly job is
 * not blocked by LLM calls. Other proxy types (e.g. Trello) have no generated code to repair: logged only.
 * Failure is never fatal: the KPI simply stays NEEDS_REPAIR for the manual "fix it" button.
 */
@Component
public class AgentCapabilityRepairTrigger implements CapabilityRepairTrigger {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityRepairTrigger.class);

    private final CapabilityAgentService service;
    private final Executor executor;

    @Autowired
    public AgentCapabilityRepairTrigger(CapabilityAgentService service) {
        this(service, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capability-repair");
            t.setDaemon(true);
            return t;
        }));
    }

    AgentCapabilityRepairTrigger(CapabilityAgentService service, Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    @Override
    public void attemptRepair(KPI kpi) {
        if (kpi.getProxyType() != ProxyType.SANDBOX_CAPABILITY) {
            log.info("KPI proxy repair: nothing to regenerate for KPI id={} proxyType={} (manual reset only)",
                    kpi.getId(), kpi.getProxyType());
            return;
        }
        executor.execute(() -> {
            try {
                KPIProxyCapability result = service.repairFor(kpi.getUserId(), kpi, true);
                log.info("KPI proxy repair for KPI id={} finished: capability v{} status={}",
                        kpi.getId(), result.getVersion(), result.getStatus());
            } catch (Exception e) {
                log.warn("KPI proxy repair for KPI id={} did not run: {}", kpi.getId(), e.toString());
            }
        });
    }
}
