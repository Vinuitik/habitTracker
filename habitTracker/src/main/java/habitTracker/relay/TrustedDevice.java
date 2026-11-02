package habitTracker.relay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A browser that may skip the TOTP step until {@code expiresAt} ("Trust this device"). Only sha256(token) is
 * stored; the token lives in the {@code relay_trust} cookie. Indexes: TrustedDeviceService.ensureIndexes().
 */
@Document("relay_trusted_devices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustedDevice {
    @Id
    private String id;
    private String userId;
    private String tokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private String userAgent;
}
