package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

// Per-user Trello credential (apiKey+token), read/written from the "Trello card count" proxy
// fields in the KPI create/edit UI (kpi-create.html, kpi-list.html). Board/list id are NOT here
// — those live per-KPI on KPI.proxyConfig via KPIController's existing /{name}/proxy endpoint, so
// this controller only ever handles the one Trello connection shared by all of a user's KPIs.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/integrations/trello")
public class TrelloCredentialController {

    private final TrelloCredentialRepository credentialRepository;

    // Never returns apiKey/token — just whether a connection already exists, so the UI can show
    // "Connected" without re-displaying (or re-requiring) the secret on every edit.
    @GetMapping("/credentials")
    public ResponseEntity<?> getStatus() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        boolean configured = credentialRepository.findByUserId(userId)
                .map(c -> !isBlank(c.getApiKey()) && !isBlank(c.getToken()))
                .orElse(false);
        return ResponseEntity.ok(Map.of("configured", configured));
    }

    @PutMapping("/credentials")
    public ResponseEntity<?> saveCredentials(@RequestBody Map<String, String> body) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String apiKey = body.get("apiKey");
        String token = body.get("token");
        if (isBlank(apiKey) || isBlank(token)) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey and token are required"));
        }

        TrelloCredential credential = credentialRepository.findByUserId(userId)
                .orElseGet(() -> TrelloCredential.builder().userId(userId).build());
        credential.setApiKey(apiKey);
        credential.setToken(token);
        credential.setUpdatedAt(LocalDateTime.now());
        credentialRepository.save(credential);

        return ResponseEntity.ok(Map.of("configured", true));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
