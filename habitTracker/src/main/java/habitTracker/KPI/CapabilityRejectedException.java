package habitTracker.KPI;

/** A proposal the server cannot grant (e.g. network/secrets, non-SERVER platform). Maps to HTTP 422. */
public class CapabilityRejectedException extends RuntimeException {
    public CapabilityRejectedException(String message) {
        super(message);
    }
}
