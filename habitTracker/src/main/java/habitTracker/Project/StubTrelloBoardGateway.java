package habitTracker.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Placeholder: no Trello call is made. Replace with the real bridge once decided.
@Component
public class StubTrelloBoardGateway implements TrelloBoardGateway {
    private static final Logger log = LoggerFactory.getLogger(StubTrelloBoardGateway.class);

    @Override
    public String createBoard(String name) {
        log.warn("[StubTrelloBoardGateway] createBoard('{}') is a stub", name);
        return "stub-" + UUID.randomUUID();
    }

    @Override
    public void deleteBoard(String boardId) {
        log.warn("[StubTrelloBoardGateway] deleteBoard({}) is a stub", boardId);
    }

    @Override
    public String linkExisting(String boardId) {
        log.warn("[StubTrelloBoardGateway] linkExisting({}) is a stub", boardId);
        return boardId;
    }
}
