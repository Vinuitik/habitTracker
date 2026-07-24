package habitTracker.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// Idempotency ledger: requestId is client-generated (uuid), so re-consuming a mailbox file
// (e.g. after a partial-batch failure and retry) is a safe no-op for anything already applied.
@Document(collection = "consumed_sync_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsumedSyncRequest {
    @Id
    private String requestId;
    private String userId;
    private long consumedAt;
}
