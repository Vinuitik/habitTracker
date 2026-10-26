package habitTracker.integrations.trello;

/** Trello failure (direct API or mongo-backup internal API). conflict=true when upstream answered 409; status = upstream HTTP status or 0. */
public class TrelloGatewayException extends RuntimeException {
    private final boolean conflict;
    private final int status;
    private final String retryAfter;

    public TrelloGatewayException(String message, boolean conflict, Throwable cause) {
        this(message, conflict, cause, 0);
    }

    public TrelloGatewayException(String message, boolean conflict, Throwable cause, int status) {
        this(message, conflict, cause, status, null);
    }

    public TrelloGatewayException(String message, boolean conflict, Throwable cause, int status, String retryAfter) {
        super(message, cause);
        this.conflict = conflict;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /** Upstream Retry-After header (429 only), else null. */
    public String getRetryAfter() { return retryAfter; }

    public boolean isConflict() { return conflict; }

    public int getStatus() { return status; }
}
