package habitTracker.KPI;

/**
 * Published (synchronously) by CapabilityLifecycleService.wire() when a capability version goes live (SHADOW or
 * ACTIVE) and the KPI is wired to it. Lets a platform react without the lifecycle knowing it: RunnerCollectorProvider
 * POSTs /activate for RUNNER collectors so the runner starts scheduling them.
 */
public record CapabilityWentLive(KPIProxyCapability capability) {}
