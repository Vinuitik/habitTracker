package habitTracker.integrations.trello;

import habitTracker.sync.VaultEncryptionService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

// Per-user Trello API credential, deliberately NOT reusing the app's own session/JWT auth
// (habitTracker.auth) — this is a third-party API key+token pair the user pastes in from
// their own Trello account, unrelated to how they're authenticated into HabitTracker itself.
// One Trello connection per user (unique index on userId): board/list id, by contrast, live on
// each KPI's own proxyConfig (see KPI.proxyConfig javadoc) since a user can point different KPIs
// at different Trello lists while sharing the same underlying API key+token.
@Document(collection = "trello_credentials")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TrelloCredential {
    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    // Trello "application key" — see https://trello.com/power-ups/admin, obtained per-user here
    // since this app has no registered Trello Power-Up of its own to hold a single shared key.
    private String apiKey;

    // Trello API token authorizing this app's apiKey to read the user's boards/lists.
    private String token;

    private LocalDateTime updatedAt;

    // true => apiKey and token hold VaultEncryptionService.encryptAtRest() ciphertext. null/false =>
    // legacy raw row, read as-is and sealed by TrelloCredentialSecretsMigration at startup or on the
    // next write. Read secrets ONLY via plain*() — the Lombok getters return what is stored.
    private Boolean secretsEncrypted;

    public String plainApiKey(VaultEncryptionService vault) {
        return reveal(vault, apiKey);
    }

    public String plainToken(VaultEncryptionService vault) {
        return reveal(vault, token);
    }

    public void storeApiKey(VaultEncryptionService vault, String plain) {
        sealSecrets(vault);
        apiKey = vault.encryptAtRest(plain);
    }

    public void storeToken(VaultEncryptionService vault, String plain) {
        sealSecrets(vault);
        token = vault.encryptAtRest(plain);
    }

    /** Encrypts legacy raw secret fields in place and sets the flag. No-op (false) if already sealed. */
    public boolean sealSecrets(VaultEncryptionService vault) {
        if (Boolean.TRUE.equals(secretsEncrypted)) return false;
        apiKey = vault.encryptAtRest(apiKey);
        token = vault.encryptAtRest(token);
        secretsEncrypted = true;
        return true;
    }

    private String reveal(VaultEncryptionService vault, String stored) {
        return Boolean.TRUE.equals(secretsEncrypted) ? vault.decryptAtRest(stored) : stored;
    }
}
