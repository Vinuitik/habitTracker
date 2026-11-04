package habitTracker.KPI;

/**
 * Where a capability runs. SERVER = the sandbox sidecar (M7). RUNNER = a collector on the user's own paired Relay
 * runner (kpi-sensors.md S3): no network, stdlib python, reads only its declared local sources.
 */
public enum CapabilityPlatform {
    SERVER,
    RUNNER,
    WINDOWS,
    ANDROID
}
