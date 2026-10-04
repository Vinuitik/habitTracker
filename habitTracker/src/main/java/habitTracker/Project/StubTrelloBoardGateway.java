package habitTracker.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Test-only (profile "stub", activated in src/test/resources/application.properties). No Trello call.
@Component
@Profile("stub")
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

    @Override
    public Map<String, Object> plan(String boardId, String description) {
        return Map.of("cards", List.of(), "proposal", Map.of());
    }

    @Override
    public Map<String, Object> apply(String boardId, Map<String, Object> body) {
        return Map.of("applied", true);
    }
}
