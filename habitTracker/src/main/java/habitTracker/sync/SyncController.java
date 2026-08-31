package habitTracker.sync;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final DriveOAuthService driveOAuthService;
    private final UserSyncSettingsRepository syncSettingsRepository;
    private final DriveService driveService;
    private final PairingCodeService pairingCodeService;

    @GetMapping("/oauth/url")
    public ResponseEntity<Map<String, String>> oauthUrl(@RequestParam String origin) {
        try {
            String redirectUri = origin + "/api/sync/oauth/callback";
            return ResponseEntity.ok(Map.of("url", driveOAuthService.buildConsentUrl(redirectUri)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam String code, @RequestParam String state,
                                               @RequestParam(required = false) String error,
                                               jakarta.servlet.http.HttpServletRequest request) {
        if (error != null) {
            return redirectTo("/connect-drive.html?drive=error");
        }
        try {
            String origin = origin(request);
            driveOAuthService.handleCallback(code, state, origin + "/api/sync/oauth/callback");
            return redirectTo("/connect-drive.html?drive=connected");
        } catch (Exception e) {
            return redirectTo("/connect-drive.html?drive=error");
        }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Void> disconnect() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        driveOAuthService.disconnect(userId);
        return ResponseEntity.ok().build();
    }

    // Called by the browser on every page load while online (js/offline/driveClient.js) so it
    // always has a fresh short-lived Drive access token cached. This is the ONLY Drive
    // credential the browser ever holds — never the durable refresh token or the OAuth client
    // secret, both of which stay server-side. When the server later becomes unreachable, the
    // browser can still push directly to Drive for as long as this bridge token remains valid
    // (usually ~1h) — after that it falls back to the local-only queue, which is safe, just delayed.
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Optional<UserSyncSettings> settingsOpt = syncSettingsRepository.findByUserId(userId);
        if (settingsOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        UserSyncSettings settings = settingsOpt.get();
        try {
            DriveService.TokenResponse token = driveService.getAccessTokenWithExpiry(settings.getDriveRefreshToken());
            long expiresAt = System.currentTimeMillis()
                    + (token.expiresInSeconds() != null ? token.expiresInSeconds() * 1000 : 3600_000L);
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("connected", true);
            body.put("driveAccessToken", token.accessToken());
            body.put("driveAccessTokenExpiresAt", expiresAt);
            body.put("mailboxFolderId", settings.getMailboxFolderId());
            body.put("encryptionKey", settings.getEncryptionKey());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // Refresh token likely revoked/expired at Google — surface as connected=false so the
            // UI can prompt a reconnect, rather than silently failing every offline push.
            return ResponseEntity.ok(Map.of("connected", false, "error", "reconnect_required"));
        }
    }

    // Session-authed: user must be looking at their own logged-in web session to mint a code.
    // The code itself becomes the credential handed to the companion device next.
    @PostMapping("/generate-pairing-code")
    public ResponseEntity<Map<String, Object>> generatePairingCode() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (syncSettingsRepository.findByUserId(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Connect Google Drive before pairing a device"));
        }
        String code = pairingCodeService.generateCode(userId);
        return ResponseEntity.ok(Map.of("code", code, "expiresInSeconds", 600));
    }

    // Deliberately UNAUTHENTICATED — no session exists on this call. The caller is a companion
    // script on a different device entirely, not a browser. Security instead comes from the code
    // itself: single-use (PairingCodeService.redeem() removes it atomically on first use),
    // short-lived (10 min), and only ever handed out to someone who was looking at their own
    // logged-in session a moment earlier (generatePairingCode() above). This is the only endpoint
    // in this controller that hands mailboxFolderId/encryptionKey to a non-browser client — every
    // other consumer of that data is either server-side (MailboxConsumeService) or a browser with
    // an active session (/status).
    @PostMapping("/pair")
    public ResponseEntity<Map<String, Object>> pair(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing code"));
        }
        Optional<String> userId = pairingCodeService.redeem(code);
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid or expired code"));
        }
        Optional<UserSyncSettings> settingsOpt = syncSettingsRepository.findByUserId(userId.get());
        if (settingsOpt.isEmpty()) {
            // Drive was disconnected between code generation and redemption.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Drive is no longer connected"));
        }
        UserSyncSettings settings = settingsOpt.get();
        return ResponseEntity.ok(Map.of(
                "mailboxFolderId", settings.getMailboxFolderId(),
                "encryptionKey", settings.getEncryptionKey()
        ));
    }

    private String origin(jakarta.servlet.http.HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto") != null
                ? request.getHeader("X-Forwarded-Proto") : request.getScheme();
        String host = request.getHeader("Host") != null ? request.getHeader("Host") : request.getServerName();
        return scheme + "://" + host;
    }

    private ResponseEntity<Void> redirectTo(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(path)).build();
    }
}
