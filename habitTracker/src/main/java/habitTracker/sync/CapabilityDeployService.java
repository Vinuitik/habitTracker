package habitTracker.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

// M6 — server -> device delivery channel, the REVERSE direction of the M5 mailbox mechanism.
// Instead of a companion writing small encrypted files the server drains (MailboxConsumeService),
// the server writes small encrypted {capabilityId, version, sourceCode} files into a SEPARATE
// per-user Drive subfolder ("_capability_deploy") that a paired companion (tools/companion/pair.py
// poll-capabilities) polls on its own schedule. Same per-user AES-256 key, same
// VaultEncryptionService wire format as the inbound mailbox — no new crypto.
//
// This is scaffolding only: capabilities aren't executable things yet (that's M8/M9). Nothing
// calls deployCapability() in production code today; it exists so the delivery channel can be
// built, tested, and proven correct ahead of the generation/execution work that will call it.
@Service
@RequiredArgsConstructor
public class CapabilityDeployService {

    // Sibling to DriveOAuthService.MAILBOX_FOLDER_NAME. Deliberately NOT created at connect time
    // (unlike the mailbox folder) — most users will never have a capability deployed to them in
    // this milestone, so there's no reason to clutter every connected user's Drive with an empty
    // folder. Created lazily, once, on the first deploy for that user.
    static final String CAPABILITY_DEPLOY_FOLDER_NAME = "_capability_deploy";

    private final UserSyncSettingsRepository syncSettingsRepository;
    private final DriveService driveService;
    private final VaultEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    /**
     * Writes one new capability version into the user's capability-deploy mailbox, encrypted
     * with their existing per-user AES key (the same one MailboxConsumeService/
     * VaultEncryptionService already use for the inbound mailbox — reused, not reinvented).
     * Returns the Drive file id.
     *
     * version is a plain monotonically-increasing integer (1, 2, 3, ...) rather than a semver
     * string — simplest possible "strictly newer" comparison for both this service and the
     * companion-side polling logic, and there's no cross-capability version-format need yet.
     */
    public String deployCapability(String userId, String capabilityId, int version, String sourceCode) {
        UserSyncSettings settings = syncSettingsRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User has not connected Google Drive: " + userId));

        String accessToken = driveService.getAccessToken(settings.getDriveRefreshToken());
        String folderId = ensureFolder(settings, accessToken);

        try {
            CapabilityPayload payload = new CapabilityPayload(capabilityId, version, sourceCode);
            byte[] plain = objectMapper.writeValueAsBytes(payload);
            byte[] wire = encryptionService.encrypt(settings.getEncryptionKey(), plain);
            // deviceId isn't relevant here (there's no per-device targeting yet — one capability
            // mailbox per user, every paired companion of that user's account sees every file),
            // so the filename just needs to be unique per write; capabilityId+version make it
            // human-scannable in the Drive UI, the uuid guards against same-version re-deploys.
            String filename = capabilityId + "-v" + version + "-" + UUID.randomUUID() + ".enc";
            return driveService.uploadFile(accessToken, folderId, filename, wire);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deploy capability " + capabilityId, e);
        }
    }

    private String ensureFolder(UserSyncSettings settings, String accessToken) {
        if (settings.getCapabilityDeployFolderId() != null) {
            return settings.getCapabilityDeployFolderId();
        }
        String folderId = driveService.findOrCreateFolder(accessToken, CAPABILITY_DEPLOY_FOLDER_NAME, settings.getDriveFolderId());
        settings.setCapabilityDeployFolderId(folderId);
        syncSettingsRepository.save(settings);
        return folderId;
    }

    public record CapabilityPayload(String capabilityId, int version, String sourceCode) {}
}
