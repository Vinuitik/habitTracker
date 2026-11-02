package habitTracker.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

// One per user who has connected their own Google Drive for offline sync. driveRefreshToken
// grants access only to files THAT USER authorized (drive.file scope) — never a shared/admin
// Drive — so one user's queued offline writes are never visible to another.
@Document(collection = "user_sync_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSyncSettings {
    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private String driveRefreshToken;
    private String driveFolderId;      // root "HabitTrackerSync" folder id in the user's own Drive
    private String mailboxFolderId;    // "_mailbox_requests" subfolder id
    private String encryptionKey;      // base64, random 256-bit AES key generated at connect time
    private String accountEmail;       // shown in the Connect Drive UI once connected

    // "_capability_deploy" subfolder id (M6) — the REVERSE-direction sibling of mailboxFolderId:
    // server writes here, a paired companion polls it. Unlike mailboxFolderId (created eagerly at
    // connect time), this is created lazily on the first CapabilityDeployService.deployCapability()
    // call, since most users will never have a capability deployed to them in this milestone.
    private String capabilityDeployFolderId;

    // true => driveRefreshToken and encryptionKey hold VaultEncryptionService.encryptAtRest() ciphertext.
    // null/false => legacy raw row: read as-is, sealed on the next write or by
    // UserSyncSettingsSecretsMigration at startup. Read secrets ONLY via plain*() — the Lombok
    // getters return what is stored (ciphertext once sealed).
    private Boolean secretsEncrypted;

    public String plainDriveRefreshToken(VaultEncryptionService vault) {
        return reveal(vault, driveRefreshToken);
    }

    public String plainEncryptionKey(VaultEncryptionService vault) {
        return reveal(vault, encryptionKey);
    }

    public void storeDriveRefreshToken(VaultEncryptionService vault, String plain) {
        sealSecrets(vault);
        driveRefreshToken = vault.encryptAtRest(plain);
    }

    public void storeEncryptionKey(VaultEncryptionService vault, String plain) {
        sealSecrets(vault);
        encryptionKey = vault.encryptAtRest(plain);
    }

    /** Encrypts legacy raw secret fields in place and sets the flag. No-op (false) if already sealed. */
    public boolean sealSecrets(VaultEncryptionService vault) {
        if (Boolean.TRUE.equals(secretsEncrypted)) return false;
        driveRefreshToken = vault.encryptAtRest(driveRefreshToken);
        encryptionKey = vault.encryptAtRest(encryptionKey);
        secretsEncrypted = true;
        return true;
    }

    private String reveal(VaultEncryptionService vault, String stored) {
        return Boolean.TRUE.equals(secretsEncrypted) ? vault.decryptAtRest(stored) : stored;
    }
}
