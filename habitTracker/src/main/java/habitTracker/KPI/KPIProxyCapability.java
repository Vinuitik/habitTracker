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

    // e.g. {"network": ["api.trello.com"], "secrets": ["trelloToken"]}. The sandbox is always
    // network=none with no secrets, so M8 refuses proposals declaring either (the enforcement
    // boundary today is: nothing non-empty is ever approved).
    private Map<String, List<String>> declaredCapabilities;

    @Builder.Default
    private CapabilityStatus status = CapabilityStatus.PENDING_TEST;
    @Builder.Default
    private Integer version = 1;
    @Builder.Default
    private Integer consecutiveFailures = 0;

    // M8 agent loop: what the user asked for, the approved written spec, and test-gate bookkeeping.
    private String description;
    private String proposal;
    // Generation attempts that produced code for this version (cap: CapabilityAgentService.MAX_ATTEMPTS).
    @Builder.Default
    private Integer attempts = 0;
    // Why the last test gate failed (or what broke, when seeding a repair); fed back into the next prompt.
    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private String lastResult;
}
