package habitTracker.Project;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.UsageLimit;
import habitTracker.relay.RunnerUnavailableException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// Runner link + repo map endpoints. Separate from ProjectController so its Trello-specific handlers don't apply.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{id}")
public class ProjectRunnerController {

    private static final Logger log = LoggerFactory.getLogger(ProjectRunnerController.class);

    private final ProjectRunnerService service;

    @PostMapping("/link")
    public Project link(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.link(id, body.get("deviceId"), body.get("path"));
    }

    @DeleteMapping("/link")
    public Project unlink(@PathVariable String id) {
        return service.unlink(id);
    }

    @PostMapping("/map")
    public ResponseEntity<Map<String, String>> startMap(@PathVariable String id) {
        return ResponseEntity.status(202).body(Map.of("jobId", service.startMap(id)));
    }

    @GetMapping("/map/job/{jobId}")
    public JsonNode pollMap(@PathVariable String id, @PathVariable String jobId,
                            @RequestParam(defaultValue = "0") long after) {
        return service.pollMap(id, jobId, after);
    }

    @GetMapping("/map")
    public ResponseEntity<Map<String, Object>> map(@PathVariable String id) {
        return service.mapSummary(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "no map yet")));
    }

    @ExceptionHandler(ProjectService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ProjectService.NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProjectRunnerService.NotLinkedException.class)
    ResponseEntity<Map<String, String>> notLinked() {
        return ResponseEntity.status(409).body(Map.of("error", "project_not_linked"));
    }

    // UI shows any of these as "Connect a runner".
    @ExceptionHandler(RunnerUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(RunnerUnavailableException e) {
        int status = switch (e.getReason()) {
            case DEVICE_NOT_FOUND -> 409;
            case OFFLINE -> 503;
            case TIMEOUT -> 504;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.code(), "message", "Connect a runner"));
    }

    // Runner 4xx passes through (e.g. bad folder path); 5xx → 502.
    @ExceptionHandler(RunnerCallException.class)
    ResponseEntity<Map<String, Object>> runnerError(RunnerCallException e) {
        log.warn("Runner call failed: status {} body {}", e.getStatus(), abbreviate(e.getBody()));
        if (UsageLimit.matches(e.getBody())) {
            return ResponseEntity.status(429).body(Map.of("error", UsageLimit.CODE,
                    "message", UsageLimit.message(e.getBody()).orElse(UsageLimit.MESSAGE)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "runner_error");
        body.put("runnerStatus", e.getStatus());
        body.put("detail", abbreviate(e.getBody()));
        int s = e.getStatus();
        return ResponseEntity.status(s >= 400 && s < 500 ? s : 502).body(body);
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
