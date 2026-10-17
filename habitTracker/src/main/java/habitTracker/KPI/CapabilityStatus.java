package habitTracker.KPI;

/** Lifecycle of a stored KPIProxyCapability. Only ACTIVE capabilities are executed (M7). */
public enum CapabilityStatus {
    PENDING_TEST,
    ACTIVE,
    DISABLED,
    NEEDS_REPAIR
}
