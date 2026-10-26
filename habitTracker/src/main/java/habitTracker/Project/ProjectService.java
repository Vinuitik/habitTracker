package habitTracker.Project;

import habitTracker.auth.SecurityUtils;
import habitTracker.integrations.trello.TrelloApiClient;
import habitTracker.integrations.trello.TrelloGatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// Every method resolves userId from SecurityUtils; by-id access goes through findByIdAndUserId
// so another user's id is indistinguishable from a missing one (404, no IDOR).
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository repository;
    private final TrelloBoardGateway boardGateway;
    private final TrelloApiClient trello;
    private final BoardSnapshotService snapshots;
    private final BoardSnapshotRepository snapshotRepository;
    private final ProjectDeleteTokenService deleteTokens;

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super("Project not found"); }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() { super("Invalid or expired confirmation token"); }
    }

    public List<ProjectView> list() {
        String userId = userId();
        Map<String, BoardSnapshot> byProject = snapshotRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(BoardSnapshot::getProjectId, Function.identity(), (x, y) -> x));
        return repository.findByUserId(userId).stream().map(p -> ProjectView.of(p, byProject.get(p.getId()))).toList();
    }

    public Project get(String id) {
        return owned(id);
    }

    /** trelloBoardId blank -> create a new board in the user's Trello; otherwise validate + adopt it (imported). */
    public Project create(String name, String description, String trelloBoardId) {
        String userId = userId();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (trelloBoardId == null || trelloBoardId.isBlank()) {
            String boardId = trello.createBoard(userId, name);
            return repository.save(Project.builder()
                    .name(name).description(description).userId(userId)
                    .trelloBoardId(boardId).createdAt(LocalDateTime.now()).build());
        }
        Map<String, Object> board = trello.getBoard(userId, trelloBoardId);
        return saveImported(userId, name, description, trelloBoardId, board);
    }

    /** Adopts the given Trello boards as projects; ids already linked by this user are skipped. */
    public List<Project> importBoards(List<String> boardIds) {
        String userId = userId();
        Set<String> seen = repository.findByUserId(userId).stream()
                .map(Project::getTrelloBoardId).collect(Collectors.toCollection(HashSet::new));
        List<Project> created = new ArrayList<>();
        for (String boardId : boardIds == null ? List.<String>of() : boardIds) {
            if (boardId == null || boardId.isBlank() || !seen.add(boardId)) continue;
            Map<String, Object> board = trello.getBoard(userId, boardId);
            String name = String.valueOf(board.get("name"));
            created.add(saveImported(userId, name, name, boardId, board));
        }
        return created;
    }

    /** The user's open Trello boards: [{id,name,url,imported}]. */
    public List<Map<String, Object>> trelloBoards() {
        String userId = userId();
        Set<String> linked = repository.findByUserId(userId).stream()
                .map(Project::getTrelloBoardId).collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : trello.listMyBoards(userId)) {
            out.add(Map.of("id", String.valueOf(b.get("id")), "name", String.valueOf(b.get("name")),
                    "url", String.valueOf(b.getOrDefault("url", "")), "imported", linked.contains(String.valueOf(b.get("id")))));
        }
        return out;
    }

    public BoardSnapshotService.Result board(String id, boolean refresh) {
        return snapshots.get(owned(id), refresh);
    }

    private Project saveImported(String userId, String name, String description, String boardId, Map<String, Object> board) {
        Project p = repository.save(Project.builder()
                .name(name).description(description).userId(userId).trelloBoardId(boardId)
                .imported(true).createdAt(LocalDateTime.now()).build());
        snapshots.store(p, board);
        return p;
    }

    public Project update(String id, String name, String description) {
        Project p = owned(id);
        if (name != null) {
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            p.setName(name);
        }
        if (description != null) p.setDescription(description);
        return repository.save(p);
    }

    public String requestDelete(String id) {
        Project p = owned(id);
        return deleteTokens.issue(p.getId(), p.getUserId());
    }

    public void delete(String id, String token) {
        Project p = owned(id);
        if (!deleteTokens.redeem(token, p.getId(), p.getUserId())) throw new InvalidTokenException();
        // Imported boards are only unlinked. Otherwise board first: if Trello fails the project stays
        // and the user can retry (needs a new token). An already-gone board (404) counts as deleted.
        if (!p.isImported() && p.getTrelloBoardId() != null) {
            try {
                trello.deleteBoard(p.getUserId(), p.getTrelloBoardId());
            } catch (TrelloGatewayException e) {
                if (e.getStatus() != 404) throw e;
            }
        }
        snapshots.delete(p.getId());
        repository.deleteById(p.getId());
    }

    public Map<String, Object> plan(String id, String description) {
        Project p = owned(id);
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description is required");
        return boardGateway.plan(boardOf(p), description);
    }

    public Map<String, Object> apply(String id, Map<String, Object> body) {
        return boardGateway.apply(boardOf(owned(id)), body);
    }

    private String boardOf(Project p) {
        if (p.getTrelloBoardId() == null || p.getTrelloBoardId().isBlank()) {
            throw new IllegalArgumentException("project has no Trello board");
        }
        return p.getTrelloBoardId();
    }

    private Project owned(String id) {
        return repository.findByIdAndUserId(id, userId()).orElseThrow(NotFoundException::new);
    }

    private String userId() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) throw new IllegalStateException("Not authenticated");
        return userId;
    }
}
