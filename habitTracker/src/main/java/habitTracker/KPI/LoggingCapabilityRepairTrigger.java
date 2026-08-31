package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * M12 stub implementation of CapabilityRepairTrigger: the real auto-repair pipeline needs M8
 * (LLM-driven capability generation), which has not landed. This bean is honest about that — it
 * logs that a repair would be triggered and does nothing else. Do not extend this to fake a real
 * repair; replace the bean entirely once M8 lands.
 */
@Component
public class LoggingCapabilityRepairTrigger implements CapabilityRepairTrigger {

    private static final Logger log = LoggerFactory.getLogger(LoggingCapabilityRepairTrigger.class);

    @Override
    public void attemptRepair(KPI kpi) {
        log.info("KPI proxy maintenance: would trigger repair pipeline here for KPI id={} name={} "
                        + "userId={} proxyType={} (no-op — real repair pipeline needs M8, not yet built)",
                kpi.getId(), kpi.getName(), kpi.getUserId(), kpi.getProxyType());
    }
}
