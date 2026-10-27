package habitTracker.Project;

import habitTracker.integrations.trello.TrelloGatewayException;

import java.util.Map;

// Java-side seam to Trello planning agent (board lifecycle is direct, see TrelloApiClient). Java holds no Trello keys; the real
// implementation (HttpTrelloBoardGateway) calls mongo-backup's internal HTTP API.
// All methods throw TrelloGatewayException on upstream failure.
public interface TrelloBoardGateway {
    /** Long-running (minutes). Returns upstream {cards, proposal} as-is. */
    Map<String, Object> plan(String boardId, String description);

    /** body may hold deadline/pace. Returns upstream result as-is. */
    Map<String, Object> apply(String boardId, Map<String, Object> body);
}
