package habitTracker.KPI;

/**
 * Hook point for M12's "auto-repair trigger": called whenever ProxyHealthService flips a KPI's
 * proxyStatus to NEEDS_REPAIR (consecutive-failure circuit breaker or an anomalous value).
 *
 * The bean for this interface is AgentCapabilityRepairTrigger (M8): for SANDBOX_CAPABILITY KPIs it
 * re-runs implementation + test gate seeded with the failure (CapabilityAgentService.repairFor).
 * ProxyHealthService only ever depends on this interface, so the implementation stays swappable.
 */
public interface CapabilityRepairTrigger {

    void attemptRepair(KPI kpi);
}
