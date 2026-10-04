package habitTracker.Project;

// Java-side seam to Trello board lifecycle. Java holds no Trello keys, so the real bridge
// (MCP internal endpoint vs. keys in javaapp) is undecided; see StubTrelloBoardGateway.
public interface TrelloBoardGateway {
    /** Creates a board and returns its id. */
    String createBoard(String name);

    void deleteBoard(String boardId);

    /** Validates/adopts an existing board and returns its id. */
    String linkExisting(String boardId);
}
