package habitTracker.KPI.deploy;

/** Where a deployment currently is on its device (observed state). */
public enum KpiDeploymentStatus { PENDING, INSTALLING, AWAITING_CONFIRM, ACTIVE, FAILED, REMOVING, REMOVED }
