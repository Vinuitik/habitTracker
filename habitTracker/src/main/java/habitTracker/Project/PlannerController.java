package habitTracker.Project;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.integrations.trello.TrelloAuthException;
import habitTracker.integrations.trello.TrelloGatewayException;
import habitTracker.integrations.trello.TrelloNotConnectedException;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerUnavailableException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// Planner chat endpoints (runner "plan" jobs) + apply to Trello. Error mapping mirrors ProjectRunnerController
// (runner) and ProjectController (Trello); kept local so neither controller's handlers change.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{id}/planner")
public class PlannerController {

    private static final Logger log = LoggerFactory.getLogger(PlannerController.class);

    private final PlannerService service;

    @PostMapping
    public Map<String, String> message(@PathVariable String id, @RequestBody Map<String, String> body) {
        return started(service.message(id, body.get("conversationId"), body.get("message")));
    }

    @GetMapping("/{conversationId}")
    public Map<String, Object> poll(@PathVariable String id, @PathVariable String conversationId,
                                    @RequestParam(defaultValue = "0") long after) {
        return service.poll(id, conversationId, after);
    }

    @PostMapping("/{conversationId}/lock")
    public Map<String, String> lock(@PathVariable String id, @PathVariable String conversationId) {
        return started(service.lock(id, conversationId));
    }

    @PostMapping("/{conversationId}/apply")
    public Map<String, Integer> apply(@PathVariable String id, @PathVariable String conversationId,
                                      @RequestBody(required = false) JsonNode body) {
        JsonNode draft = body == null ? null : body.get("draft");
        String deadline = body == null || !body.path("deadline").isTextual() ? null : body.get("deadline").asText();
        String pace = body == null || body.path("pace").isNull() || body.path("pace").isMissingNode() ? null : body.get("pace").asText();
        return Map.of("created", service.apply(id, conversationId, draft, deadline, pace));
    }

    private static Map<String, String> started(PlannerService.Started s) {
        return Map.of("conversationId", s.conversationId(), "jobId", s.jobId());
    }

    @ExceptionHandler(ProjectService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ProjectService.NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProjectRunnerService.NotLinkedException.class)
    ResponseEntity<Map<String, String>> notLinked() {
        return ResponseEntity.status(409).body(Map.of("error", "project_not_linked"));
    }

    @ExceptionHandler(PlannerService.AlreadyAppliedException.class)
    ResponseEntity<Map<String, String>> alreadyApplied() {
        return ResponseEntity.status(409).body(Map.of("error", "planner_already_applied"));
    }

    @ExceptionHandler(PlannerService.BusyException.class)
    ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(409).body(Map.of("error", "planner_busy"));
    }

    @ExceptionHandler(RunnerUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(RunnerUnavailableException e) {
        int status = switch (e.getReason()) {
            case DEVICE_NOT_FOUND -> 409;
            case OFFLINE -> 503;
            case TIMEOUT -> 504;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.code(), "message", "Connect a runner"));
    }

    @ExceptionHandler(RunnerCallException.class)
    ResponseEntity<Map<String, Object>> runnerError(RunnerCallException e) {
        log.warn("Runner call failed: status {} body {}", e.getStatus(), abbreviate(e.getBody()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "runner_error");
        body.put("runnerStatus", e.getStatus());
        body.put("detail", abbreviate(e.getBody()));
        int s = e.getStatus();
        return ResponseEntity.status(s >= 400 && s < 500 ? s : 502).body(body);
    }

    @ExceptionHandler(TrelloGatewayException.class)
    ResponseEntity<Map<String, String>> trelloUpstream(TrelloGatewayException e) {
        log.warn("Trello upstream failure during planner apply (trello status {}): {}", e.getStatus(), e.getMessage());
        if (e.getStatus() == 429) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(429);
            if (e.getRetryAfter() != null) b.header("Retry-After", e.getRetryAfter());
            return b.body(Map.of("error", "trello_rate_limited"));
        }
        return ResponseEntity.status(502).body(Map.of("error", "trello_error"));
    }

    @ExceptionHandler(TrelloNotConnectedException.class)
    ResponseEntity<Map<String, String>> trelloNotConnected() {
        return ResponseEntity.status(412).body(Map.of("error", "trello_not_connected"));
    }

    @ExceptionHandler(TrelloAuthException.class)
    ResponseEntity<Map<String, String>> trelloBadToken() {
        return ResponseEntity.status(412).body(Map.of("error", "trello_token_invalid"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unauth(IllegalStateException e) {
        return ResponseEntity.status(401).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
