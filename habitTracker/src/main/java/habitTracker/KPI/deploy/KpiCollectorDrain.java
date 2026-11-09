package habitTracker.KPI.deploy;

/** Final read of an old collector version before the reconciler deletes it (implemented by RunnerCollectorProvider). */
public interface KpiCollectorDrain {
    /**
     * Pulls the row's unread readings into the KPI. Runner offline: RunnerUnavailableException (caller must not delete).
     * A no-op when the KPI has no live version (retire) or the collector is already gone from the runner.
     */
    void drain(KpiDeployment dep);
}
