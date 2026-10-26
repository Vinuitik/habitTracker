package habitTracker.integrations.trello;

/** Trello failure (direct API or mongo-backup internal API). conflict=true when upstream answered 409; status = upstream HTTP status or 0. */
public class TrelloGatewayException extends RuntimeException {
    private final boolean conflict;
    private final int status;

    public TrelloGatewayException(String message, boolean conflict, Throwable cause) {
        this(message, conflict, cause, 0);
    }

    public TrelloGatewayException(String message, boolean conflict, Throwable cause, int status) {
        super(message, cause);
        this.conflict = conflict;
        this.status = status;
    }

    public boolean isConflict() { return conflict; }

    public int getStatus() { return status; }
}
