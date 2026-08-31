package habitTracker.KPI;

/**
 * Health state of a KPI's automatic proxy (M12 maintenance loop). ACTIVE (the default) means the
 * nightly proxy-fill step (habitTracker.updater.KPIProxyFillService) is allowed to keep calling
 * this KPI's ProxyProvider. NEEDS_REPAIR means the circuit breaker has tripped — either
 * KPI.consecutiveProxyFailures reached the configured threshold, or ProxyHealthService's anomaly
 * check flagged a fetched value as wildly outside the KPI's recent historical range — and the
 * proxy-fill step skips this KPI entirely until a human resets it
 * (KPIService.resetProxyHealth / PUT /api/kpis/{name}/proxy/reset).
 */
public enum ProxyStatus {
    ACTIVE,
    NEEDS_REPAIR
}
