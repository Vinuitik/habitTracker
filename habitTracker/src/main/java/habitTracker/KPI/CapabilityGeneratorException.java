package habitTracker.KPI;

public class CapabilityGeneratorException extends RuntimeException {

    /** User-facing text when the KPI owner has no runner online (also the repair "waiting" marker). */
    public static final String NO_RUNNER_MESSAGE = "Connect a runner to generate this capability";

    private final boolean busy;
    private final boolean noRunner;

    public CapabilityGeneratorException(String message, boolean busy, Throwable cause) {
        this(message, busy, false, cause);
    }

    private CapabilityGeneratorException(String message, boolean busy, boolean noRunner, Throwable cause) {
        super(message, cause);
        this.busy = busy;
        this.noRunner = noRunner;
    }

    public static CapabilityGeneratorException noRunner(Throwable cause) {
        return new CapabilityGeneratorException(NO_RUNNER_MESSAGE, false, true, cause);
    }

    /** True when the runner rejected us as busy (HTTP 409/429): another job is running. */
    public boolean isBusy() {
        return busy;
    }

    /** True when no paired runner of the KPI owner is online (or it dropped mid-job). */
    public boolean isNoRunner() {
        return noRunner;
    }
}
