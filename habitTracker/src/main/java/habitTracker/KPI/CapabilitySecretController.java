package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Write-only secrets for sandbox capabilities. Always scoped to SecurityUtils.getCurrentUserId().
 * No endpoint ever returns a value: list gives names, PUT/DELETE give {name, set}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kpi-secrets")
public class CapabilitySecretController {

    private final CapabilitySecretService service;

    @GetMapping
    public ResponseEntity<?> list() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return unauthenticated();
        return ResponseEntity.ok(Map.of("names", service.listNames(userId)));
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> set(@PathVariable String name, @RequestBody Map<String, String> body) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return unauthenticated();
        service.set(userId, name, body.get("value"));
        return ResponseEntity.ok(Map.of("name", name, "set", true));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return unauthenticated();
        service.delete(userId, name);
        return ResponseEntity.ok(Map.of("name", name, "set", false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
    }
}
