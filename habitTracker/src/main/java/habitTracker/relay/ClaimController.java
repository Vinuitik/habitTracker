package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** QR pairing endpoints (ClaimService, shared/HUB.md "QR pairing"). */
@RestController
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claims;

    /** runnerd pair (no --code): no session, no CSRF, not 2FA-gated. */
    @PostMapping("/relay/pair/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> body) {
        Object name = body == null ? null : body.get("name");
        return claims.start(name instanceof String n ? n : null)
                .<ResponseEntity<Map<String, Object>>>map(s -> ResponseEntity.ok(Map.of(
                        "claimId", s.claimId(), "pollSecret", s.pollSecret(),
                        "expiresInSeconds", ClaimService.TTL_SECONDS)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("error", "too many pending pairings, try again in a few minutes")));
    }

    /** runnerd pair: 202 while waiting, 200 + token once approved, 404 when declined, expired or unknown. */
    @PostMapping("/relay/pair/poll")
    public ResponseEntity<Map<String, Object>> poll(@RequestBody(required = false) Map<String, Object> body) {
        Object id = body == null ? null : body.get("claimId");
        Object secret = body == null ? null : body.get("pollSecret");
        ClaimService.PollResult r = claims.poll(id instanceof String s ? s : null, secret instanceof String s ? s : null);
        return switch (r.status()) {
            case PENDING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "pending"));
            case APPROVED -> ResponseEntity.ok(Map.of("deviceId", r.paired().deviceId(), "token", r.paired().token()));
            case GONE -> notFound();
        };
    }

    /** Browser (session + CSRF + TOTP): the confirm page's "Add <name>?". */
    @GetMapping("/relay/api/claims/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return claims.pendingName(id)
                .<ResponseEntity<Map<String, Object>>>map(n -> ResponseEntity.ok(Map.of("name", n)))
                .orElseGet(ClaimController::notFound);
    }

    @PostMapping("/relay/api/claims/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        return claims.approve(SecurityUtils.getCurrentUserId(), id)
                .<ResponseEntity<Map<String, Object>>>map(p -> ResponseEntity.ok(Map.of("deviceId", p.deviceId())))
                .orElseGet(ClaimController::notFound);
    }

    @PostMapping("/relay/api/claims/{id}/deny")
    public ResponseEntity<?> deny(@PathVariable String id) {
        return claims.deny(id) ? ResponseEntity.noContent().build() : notFound();
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "pairing expired, declined or unknown"));
    }
}
