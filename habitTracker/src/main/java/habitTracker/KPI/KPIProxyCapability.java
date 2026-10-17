package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Stored, user-scoped script that produces a KPI value (design doc "Data model"). Read fresh
 * from Mongo right before every run, so editing it needs no redeploy. M7 only executes
 * platform=SERVER, language="python", status=ACTIVE capabilities.
 */
@Document(collection = "kpi_proxy_capabilities")
@CompoundIndex(def = "{'kpiId': 1, 'userId': 1}")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPIProxyCapability {
    @Id
    private String id;
    private String userId;
    private String kpiId;

    @Builder.Default
    private CapabilityPlatform platform = CapabilityPlatform.SERVER;
    @Builder.Default
    private String language = "python";
    private String sourceCode;

    // e.g. {"network": ["api.trello.com"], "secrets": ["trelloToken"]}. Not enforced by M7
    // (sandbox is always network=none, no secrets); recorded for M8+.
    private Map<String, List<String>> declaredCapabilities;

    @Builder.Default
    private CapabilityStatus status = CapabilityStatus.PENDING_TEST;
    @Builder.Default
    private Integer version = 1;
    @Builder.Default
    private Integer consecutiveFailures = 0;

    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private String lastResult;
}
