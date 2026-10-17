package habitTracker.KPI;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * M8 agent loop API. Every call resolves the KPI by (name, current user) and capabilities by
 * (id, user, kpi) inside CapabilityAgentService, so one user can never see or drive another's.
 * propose/approve/regenerate/repair block while the LLM + sandbox run (up to a few minutes).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpis/{name}/capability")
public class CapabilityAgentController {

    private final CapabilityAgentService service;

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

    // Manual "fix it" (M12): same pipeline as the automatic repair trigger.
    @PostMapping("/repair")
    public CapabilityDTO repair(@PathVariable String name) {
        return service.repair(name);
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
        return ResponseEntity.status(e.isBusy() ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage()));
    }
}
