package habitTracker.KPI;

public class CapabilityGeneratorException extends RuntimeException {
    private final boolean busy;

    public CapabilityGeneratorException(String message, boolean busy, Throwable cause) {
        super(message, cause);
        this.busy = busy;
    }

    /** True when the single-flight lock upstream rejected us (HTTP 409): another generation is running. */
    public boolean isBusy() {
        return busy;
    }
}
