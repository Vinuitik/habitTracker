package habitTracker.Project;

/** Upstream (mongo-backup internal API) failure. conflict=true when upstream answered 409. */
public class TrelloGatewayException extends RuntimeException {
    private final boolean conflict;

    public TrelloGatewayException(String message, boolean conflict, Throwable cause) {
        super(message, cause);
        this.conflict = conflict;
    }

    public boolean isConflict() { return conflict; }
}
