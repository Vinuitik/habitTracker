package habitTracker.relay;

/**
 * The user's runner can't be reached: device unknown / not theirs, no live uplink, or no answer in time.
 * The UI shows all of these as "Connect a runner" (reason tells which).
 */
public class RunnerUnavailableException extends RuntimeException {

    public enum Reason { DEVICE_NOT_FOUND, OFFLINE, TIMEOUT }

    private final Reason reason;

    public RunnerUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    /** Stable error code for JSON bodies. */
    public String code() {
        return switch (reason) {
            case DEVICE_NOT_FOUND -> "runner_not_found";
            case OFFLINE -> "runner_offline";
            case TIMEOUT -> "runner_timeout";
        };
    }
}
