package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * M8 agent loop API. Every call resolves the KPI by (name, current user) and capabilities by
 * (id, user, kpi) inside CapabilityAgentService, so one user can never see or drive another's.
 * propose/approve/regenerate/repair block while the LLM + sandbox run (up to a few minutes); kept for API
 * callers and tests. The UI uses the async /jobs endpoints (CapabilityTaskService): start → {jobId}, then
 * poll ?after=nextSeq → {state, events, nextSeq, result?, error?, code?, usage?}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpis/{name}/capability")
public class CapabilityAgentController {

    private final CapabilityAgentService service;
    private final CapabilityTaskService tasks;

    @GetMapping
    public ResponseEntity<?> latest(@PathVariable String name) {
        return service.latest(name).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/propose")
    public CapabilityDTO propose(@PathVariable String name, @RequestBody Map<String, Object> body) {
        Object d = body.get("description");
        return service.propose(name, d == null ? null : d.toString());
    }

    @PostMapping("/{id}/approve")
    public CapabilityDTO approve(@PathVariable String name, @PathVariable String id) {
        return service.approve(name, id);
    }

    @PostMapping("/{id}/reject")
    public CapabilityDTO reject(@PathVariable String name, @PathVariable String id) {
        return service.reject(name, id);
    }

    @PostMapping("/{id}/regenerate")
    public CapabilityDTO regenerate(@PathVariable String name, @PathVariable String id) {
        return service.regenerate(name, id);
    }

    /** Confirm gate "Looks right": AWAITING_CONFIRM -> SHADOW. */
    @PostMapping("/{id}/confirm")
    public CapabilityDTO confirm(@PathVariable String name, @PathVariable String id) {
        return service.confirm(name, id);
    }

    /** Confirm gate "Wrong, because...": body {reason}. Blocking; the UI uses the async job action "wrong" instead. */
    @PostMapping("/{id}/wrong")
    public CapabilityDTO wrong(@PathVariable String name, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.wrong(name, id, str(body.get("reason")));
    }

    // Manual "fix it" (M12): same pipeline as the automatic repair trigger.
    @PostMapping("/repair")
    public CapabilityDTO repair(@PathVariable String name) {
        return service.repair(name);
    }

    // ── async (UI) ──────────────────────────────────────────────────────────

    /** body: {action: propose|approve|regenerate|repair|wrong, id?, description? (= the reason for "wrong")} → 202 {jobId}. */
    @PostMapping("/jobs")
    public ResponseEntity<?> startJob(@PathVariable String name, @RequestBody Map<String, Object> body) {
        String jobId = tasks.start(currentUser(), name, str(body.get("action")), str(body.get("id")),
                str(body.get("description")));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
    }

    /** The KPI's running job, if any → {jobId} or 204. Lets a reopened dialog resume watching. */
    @GetMapping("/jobs/active")
    public ResponseEntity<?> activeJob(@PathVariable String name) {
        return tasks.active(currentUser(), name).<ResponseEntity<?>>map(id -> ResponseEntity.ok(Map.of("jobId", id)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> pollJob(@PathVariable String name, @PathVariable String jobId,
                                     @RequestParam(defaultValue = "0") long after) {
        return tasks.poll(currentUser(), name, jobId, after).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "This job has expired. Reopen the dialog")));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String currentUser() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) throw new IllegalStateException("Not authenticated");
        return userId;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CapabilityRejectedException.class)
    ResponseEntity<?> rejected(CapabilityRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CapabilityGeneratorException.class)
    ResponseEntity<?> generator(CapabilityGeneratorException e) {
        HttpStatus status = e.isBusy() ? HttpStatus.CONFLICT
                : e.isNoRunner() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(Map.of("error", e.getMessage()));
    }
}
