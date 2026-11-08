package habitTracker.relay;

/** Published when a runner's uplink socket is established (the deployment reconciler converges that device). */
public record RunnerConnected(String userId, String deviceId) {}
