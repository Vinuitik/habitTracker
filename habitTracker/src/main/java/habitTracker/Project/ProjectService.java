package habitTracker.Project;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

// Every method resolves userId from SecurityUtils; by-id access goes through findByIdAndUserId
// so another user's id is indistinguishable from a missing one (404, no IDOR).
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository repository;
    private final TrelloBoardGateway boardGateway;
    private final ProjectDeleteTokenService deleteTokens;

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super("Project not found"); }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() { super("Invalid or expired confirmation token"); }
    }

    public List<Project> list() {
        return repository.findByUserId(userId());
    }

    public Project get(String id) {
        return owned(id);
    }

    /** trelloBoardId blank -> create a new board; otherwise link the existing one. */
    public Project create(String name, String description, String trelloBoardId) {
        String userId = userId();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String boardId = (trelloBoardId == null || trelloBoardId.isBlank())
                ? boardGateway.createBoard(name)
                : boardGateway.linkExisting(trelloBoardId);
        return repository.save(Project.builder()
                .name(name).description(description).userId(userId)
                .trelloBoardId(boardId).createdAt(LocalDateTime.now()).build());
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
        // Board first: if Trello fails the project stays and the user can retry (needs a new token).
        if (p.getTrelloBoardId() != null) boardGateway.deleteBoard(p.getTrelloBoardId());
        repository.deleteById(p.getId());
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
