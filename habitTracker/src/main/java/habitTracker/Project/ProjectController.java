package habitTracker.Project;

import habitTracker.integrations.trello.TrelloGatewayException;

import lombok.RequiredArgsConstructor;
import habitTracker.integrations.trello.TrelloAuthException;
import habitTracker.integrations.trello.TrelloNotConnectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;
    private final ProjectBoardService boardService;

    @GetMapping
    public List<ProjectView> list() {
        return service.list();
    }

    @GetMapping("/trello/boards")
    public List<Map<String, Object>> trelloBoards() {
        return service.trelloBoards();
    }

    @PostMapping("/import")
    public ResponseEntity<List<Project>> importBoards(@RequestBody Map<String, List<String>> body) {
        return ResponseEntity.status(201).body(service.importBoards(body.get("trelloBoardIds")));
    }

    @GetMapping("/{id}/board")
    public ResponseEntity<BoardSnapshot> board(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
        BoardSnapshotService.Result r = service.board(id, refresh);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (r.stale()) b.header("X-Stale", "true");
        return b.body(r.snapshot());
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Project> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.status(201)
                .body(service.create(body.get("name"), body.get("description"), body.get("trelloBoardId")));
    }

    @PutMapping("/{id}")
    public Project update(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.update(id, body.get("name"), body.get("description"));
    }

    @PostMapping("/{id}/delete-request")
    public Map<String, Object> deleteRequest(@PathVariable String id) {
        return Map.of("token", service.requestDelete(id), "expiresInSeconds", 60);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(required = false) String token) {
        service.delete(id, token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/plan")
    public Map<String, Object> plan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object d = body.get("description");
        return service.plan(id, d == null ? null : d.toString());
    }

    @PostMapping("/{id}/apply")
    public Map<String, Object> apply(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return service.apply(id, body);
    }

    // ---- write-through board editing (Trello first, then snapshot patch) ----

    @PostMapping("/{id}/lists")
    public BoardSnapshot.ListView createList(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return boardService.createList(id, body);
    }

    @PatchMapping("/{id}/lists/{listId}")
    public BoardSnapshot.ListView updateList(@PathVariable String id, @PathVariable String listId, @RequestBody Map<String, Object> body) {
        return boardService.updateList(id, listId, body);
    }

    @PostMapping("/{id}/cards")
    public BoardSnapshot.CardView createCard(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return boardService.createCard(id, body);
    }

    @PatchMapping("/{id}/cards/{cardId}")
    public BoardSnapshot.CardView updateCard(@PathVariable String id, @PathVariable String cardId, @RequestBody Map<String, Object> body) {
        return boardService.updateCard(id, cardId, body);
    }

    @PostMapping("/{id}/cards/{cardId}/complete")
    public BoardSnapshot.CardView complete(@PathVariable String id, @PathVariable String cardId) {
        return boardService.complete(id, cardId);
    }

    @PostMapping("/{id}/cards/{cardId}/reopen")
    public BoardSnapshot.CardView reopen(@PathVariable String id, @PathVariable String cardId) {
        return boardService.reopen(id, cardId);
    }

    @ExceptionHandler(TrelloGatewayException.class)
    ResponseEntity<Map<String, String>> upstream(TrelloGatewayException e) {
        if (e.getStatus() == 429) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(429);
            if (e.getRetryAfter() != null) b.header("Retry-After", e.getRetryAfter());
            return b.body(Map.of("error", "trello_rate_limited"));
        }
        return ResponseEntity.status(e.isConflict() ? 409 : 502).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TrelloNotConnectedException.class)
    ResponseEntity<Map<String, String>> notConnected() {
        return ResponseEntity.status(412).body(Map.of("error", "trello_not_connected"));
    }

    @ExceptionHandler(TrelloAuthException.class)
    ResponseEntity<Map<String, String>> badTrelloToken() {
        return ResponseEntity.status(412).body(Map.of("error", "trello_token_invalid"));
    }

    @ExceptionHandler(ProjectService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ProjectService.NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProjectService.InvalidTokenException.class)
    ResponseEntity<Map<String, String>> badToken(ProjectService.InvalidTokenException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unauth(IllegalStateException e) {
        return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
}
