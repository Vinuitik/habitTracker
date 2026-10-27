package habitTracker.integrations.trello;

/** The user has no (or blank) Trello apiKey/token on file. */
public class TrelloNotConnectedException extends RuntimeException {
    public TrelloNotConnectedException() { super("Trello is not connected"); }
}
