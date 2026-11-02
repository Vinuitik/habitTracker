package habitTracker.auth;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;
    private String googleId;
    private String name;

    // Second factor for Relay (habitTracker.relay.TwoFactorService). The secret is AES-GCM ciphertext,
    // never plaintext: it can't be hashed because the server recomputes codes from it.
    private String totpSecret;
    private boolean totpEnabled;
    // Last accepted 30s step; a code is rejected unless its step is newer (no replay).
    private Long totpLastStep;
}
