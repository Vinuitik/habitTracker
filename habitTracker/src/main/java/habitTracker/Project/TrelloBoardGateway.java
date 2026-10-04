package habitTracker.Project;

import java.util.Map;

// Java-side seam to Trello board lifecycle + planning agent. Java holds no Trello keys; the real
// implementation (HttpTrelloBoardGateway) calls mongo-backup's internal HTTP API.
// All methods throw TrelloGatewayException on upstream failure.
public interface TrelloBoardGateway {
    /** Creates a board and returns its id. */
    String createBoard(String name);

    void deleteBoard(String boardId);

    /** Validates/adopts an existing board and returns its id. */
    String linkExisting(String boardId);

    /** Long-running (minutes). Returns upstream {cards, proposal} as-is. */
    Map<String, Object> plan(String boardId, String description);

    /** body may hold deadline/pace. Returns upstream result as-is. */
    Map<String, Object> apply(String boardId, Map<String, Object> body);
}
