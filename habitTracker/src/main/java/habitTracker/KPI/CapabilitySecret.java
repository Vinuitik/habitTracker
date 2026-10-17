package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A user's named secret for sandbox capabilities (e.g. an API token). Value is AES-256-GCM encrypted via
 * VaultEncryptionService (base64 of [12B IV][ciphertext+tag]); the plaintext never touches Mongo and is
 * never returned by any API. Unique per (userId, name) by construction: id = userId + ":" + name (the app
 * does not auto-create Mongo indexes, so uniqueness must not depend on one).
 */
@Document(collection = "kpi_capability_secrets")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class CapabilitySecret {
    @Id
    private String id; // see idFor()
    private String userId;
    private String name;
    private String valueEnc;
    private LocalDateTime updatedAt;

    public static String idFor(String userId, String name) {
        return userId + ":" + name;
    }

    @Override
    public String toString() {
        return "CapabilitySecret{userId=" + userId + ", name=" + name + "}";
    }
}
