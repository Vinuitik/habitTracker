package habitTracker.integrations.trello;

/** Trello answered 401: the stored apiKey/token is invalid or revoked. */
public class TrelloAuthException extends RuntimeException {
    public TrelloAuthException() { super("Trello token is invalid"); }
}
