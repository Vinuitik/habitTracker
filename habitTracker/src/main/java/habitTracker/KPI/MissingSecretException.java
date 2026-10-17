package habitTracker.KPI;

import java.util.List;

/** A capability declares secrets its owner has not set. Becomes a test-gate / run failure, never a run. */
public class MissingSecretException extends RuntimeException {
    private final List<String> names;

    public MissingSecretException(List<String> names) {
        super("secret " + String.join(", ", names) + " not set");
        this.names = names;
    }

    public List<String> names() {
        return names;
    }
}
