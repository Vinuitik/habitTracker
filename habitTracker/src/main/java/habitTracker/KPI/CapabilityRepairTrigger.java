package habitTracker.KPI;

/**
 * Hook point for M12's "auto-repair trigger": called whenever ProxyHealthService flips a KPI's
 * proxyStatus to NEEDS_REPAIR (consecutive-failure circuit breaker or an anomalous value).
 *
 * There is no real repair pipeline yet — that needs M8 (LLM-driven capability generation), which
 * has not landed. The bean registered for this interface today (LoggingCapabilityRepairTrigger)
 * only logs that a repair would be triggered here; it does not re-run any proposal/implementation/
 * test-gate pipeline. Swap it for a real implementation once M8 exists — ProxyHealthService only
 * ever depends on this interface, so the replacement is a drop-in, same pattern as
 * ProxyProviderRegistry/ProxyProvider in M1.
 */
public interface CapabilityRepairTrigger {

    void attemptRepair(KPI kpi);
}
