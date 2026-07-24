package habitTracker.sync;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Per-user incremental OAuth consent for drive.file scope — separate from the app's Google
// *login* (which only ever asks for openid,email,profile). Each user who connects gets their
// own refresh token stored against their own userId (UserSyncSettings), so one user's Drive
// mailbox is never reachable by another user's credentials.
@Service
@RequiredArgsConstructor
public class DriveOAuthService {

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final long STATE_TTL_MS = 10 * 60 * 1000;
    private static final String ROOT_FOLDER_NAME = "HabitTrackerSync";
    private static final String MAILBOX_FOLDER_NAME = "_mailbox_requests";

    private final DriveService driveService;
    private final VaultEncryptionService encryptionService;
    private final UserSyncSettingsRepository syncSettingsRepository;

    // Single-instance in-memory nonce store — fine for a single javaapp container (no clustering).
    // A backend restart mid-consent aborts the flow; the user just clicks Connect again.
    private final Map<String, Long> pendingStates = new ConcurrentHashMap<>();

    public String buildConsentUrl(String redirectUri) {
        if (!driveService.isConfigured()) {
            throw new IllegalStateException(
                    "Drive sync is not configured on this server (GOOGLE_OAUTH_CLIENT_ID/SECRET unset)");
        }
        String state = UUID.randomUUID().toString();
        pendingStates.put(state, System.currentTimeMillis() + STATE_TTL_MS);
        return UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("client_id", driveService.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    public void handleCallback(String code, String state, String redirectUri) {
        Long expiry = pendingStates.remove(state);
        if (expiry == null || expiry < System.currentTimeMillis()) {
            throw new IllegalStateException("Invalid or expired OAuth state — please click Connect again");
        }
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("Must be logged in to connect Drive");
        }

        DriveService.TokenResponse tokens = driveService.exchangeCode(code, redirectUri);
        if (tokens.refreshToken() == null) {
            // access_type=offline&prompt=consent forces Google to return one on first consent;
            // guard anyway since a stale prior grant can suppress it.
            throw new IllegalStateException(
                    "Google did not return a refresh token — revoke prior access at https://myaccount.google.com/permissions and try again");
        }

        String accessToken = tokens.accessToken();
        String rootFolderId = driveService.findOrCreateFolder(accessToken, ROOT_FOLDER_NAME, null);
        String mailboxFolderId = driveService.findOrCreateFolder(accessToken, MAILBOX_FOLDER_NAME, rootFolderId);

        UserSyncSettings settings = syncSettingsRepository.findByUserId(userId)
                .orElseGet(() -> UserSyncSettings.builder().userId(userId).build());
        settings.setDriveRefreshToken(tokens.refreshToken());
        settings.setDriveFolderId(rootFolderId);
        settings.setMailboxFolderId(mailboxFolderId);
        if (settings.getEncryptionKey() == null) {
            settings.setEncryptionKey(encryptionService.generateKey());
        }
        syncSettingsRepository.save(settings);
    }

    public void disconnect(String userId) {
        syncSettingsRepository.deleteByUserId(userId);
    }
}
