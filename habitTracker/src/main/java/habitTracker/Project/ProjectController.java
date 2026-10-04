package habitTracker.Project;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    @GetMapping
    public List<Project> list() {
        return service.list();
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

    @ExceptionHandler(TrelloGatewayException.class)
    ResponseEntity<Map<String, String>> upstream(TrelloGatewayException e) {
        return ResponseEntity.status(e.isConflict() ? 409 : 502).body(Map.of("error", e.getMessage()));
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
