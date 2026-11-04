package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
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

    // e.g. {"network": ["api.trello.com"], "secrets": ["TRELLO_TOKEN"]}. Approval = the grant: at run time
    // exactly these hosts become reachable (via the egress proxy) and exactly these secrets are injected
    // as env vars; no declared hosts => --network none. Validated by CapabilityGrants.
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
    // Optional plain-language summaries from the proposal (what is counted / where it comes from); UI only.
    private String measures;
    private String dataSource;
    // Generation attempts that produced code for this version (cap: CapabilityAgentService.MAX_ATTEMPTS).
    @Builder.Default
    private Integer attempts = 0;
    // Why the last test gate failed (or what broke, when seeding a repair); fed back into the next prompt.
    private String failureReason;

    // S1 confirm gate / shadow (additive, all nullable: pre-S1 documents read back unchanged).
    // The test-gate sample the user is asked about: value, unit, evidence rows, and the day it was computed for.
    private Double sampleValue;
    private String sampleUnit;
    private List<CapabilityOutput.Row> sampleEvidence;
    private LocalDate sampleDate;
    // How many times the user answered "Wrong" for this version (cap: CapabilityAgentService.MAX_CONFIRM_ROUNDS).
    private Integer confirmRounds;
    // SHADOW runs until this date (exclusive of later readings), then the nightly promotion makes it ACTIVE.
    private LocalDate shadowUntil;
    // True for versions created by repair of a live capability: they skip AWAITING_CONFIRM.
    private Boolean repair;
    // Unit of the last production run (set by the provider); feeds the plausibility guard.
    private String lastUnit;

    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private String lastResult;
}
