package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService devices;
    private final UplinkRegistry uplinks;

    /** Browser, behind session + CSRF + TOTP. */
    @PostMapping("/relay/api/devices/pairing-code")
    public Map<String, Object> pairingCode() {
        String code = devices.newPairingCode(SecurityUtils.getCurrentUserId());
        return Map.of("code", code, "expiresInSeconds", DeviceService.CODE_TTL_SECONDS);
    }

    /** runnerd pair: no session, no CSRF, not 2FA-gated — the single-use code is the credential. */
    @PostMapping("/relay/pair")
    public ResponseEntity<Map<String, Object>> pair(@RequestBody(required = false) Map<String, Object> body) {
        Object code = body == null ? null : body.get("code");
        if (!(code instanceof String c) || c.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "code is required"));
        }
        Object name = body.get("name");
        return devices.pair(c, name instanceof String n ? n : null)
                .<ResponseEntity<Map<String, Object>>>map(p ->
                        ResponseEntity.ok(Map.of("deviceId", p.deviceId(), "token", p.token())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "invalid, used or expired code")));
    }

    @GetMapping("/relay/api/devices")
    public List<Map<String, Object>> list() {
        return devices.list(SecurityUtils.getCurrentUserId()).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("online", uplinks.isOnline(d.getId()));
            m.put("lastSeenAt", d.getLastSeenAt() == null ? null : d.getLastSeenAt().toString());
            return m;
        }).toList();
    }

    @DeleteMapping("/relay/api/devices/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return devices.owned(SecurityUtils.getCurrentUserId(), id).<ResponseEntity<?>>map(d -> {
            devices.delete(d);
            uplinks.disconnect(d.getId());
            return ResponseEntity.noContent().build();
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not found")));
    }
}
