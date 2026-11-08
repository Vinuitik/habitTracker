package habitTracker.KPI;

/** A proposal the server cannot grant (e.g. a KPI that needs data a runner collector cannot read). Maps to HTTP 422. */
public class CapabilityRejectedException extends RuntimeException {
    public CapabilityRejectedException(String message) {
        super(message);
    }
}
