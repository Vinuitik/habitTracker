package habitTracker.KPI;

/**
 * Where a single KPIData point came from. MANUAL is the default and covers every data point
 * written before this field existed (see KPIData.source javadoc for the deserialization
 * guarantee). AUTOFILL is the pre-existing default-fill cron (KPIDefaultFillService).
 * PROXY_TRELLO / PROXY_CAPABILITY are for the M1 proxy-provider mechanism
 * (habitTracker.updater.KPIProxyFillService) — PROXY_CAPABILITY is produced by
 * SandboxCapabilityProvider (M7, ProxyType.SANDBOX_CAPABILITY).
 */
public enum KPIDataSource {
    MANUAL,
    PROXY_TRELLO,
    PROXY_CAPABILITY,
    AUTOFILL
}
