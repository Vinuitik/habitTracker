package habitTracker.relay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A paired runner machine. Only sha256(token) is stored; the token itself lives in the runner's hub.json. */
@Document("relay_devices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device {
    @Id
    private String id;
    @Indexed
    private String userId;
    private String name;
    @Indexed(unique = true)
    private String tokenHash;
    private Instant createdAt;
    private Instant lastSeenAt;
}
