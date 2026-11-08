package habitTracker.KPI;

/**
 * Where a capability runs. RUNNER = a collector on the user's own paired Relay runner (kpi-sensors.md S3): no
 * network, stdlib python, reads only its declared local sources. It is the only platform generated today
 * (runner-only-kpis.md: no user-influenced code runs on the hub). ANDROID is a reserved placeholder [NOT IMPLEMENTED].
 * Old SERVER/WINDOWS documents are rewritten by RunnerOnlyKpiMigration (platform null, status RETIRED).
 */
public enum CapabilityPlatform {
    RUNNER,
    ANDROID
}
