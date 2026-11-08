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
 * from Mongo right before every run, so editing it needs no redeploy. Only RUNNER collectors are built and
 * run (RunnerCollectorPipeline / RunnerCollectorProvider); platform is null on RETIRED ex-SERVER documents.
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
    private CapabilityPlatform platform = CapabilityPlatform.RUNNER;
    @Builder.Default
    private String language = "python";
    private String sourceCode;

    // RUNNER: {"sources": ["activitywatch", "file"]}, the local source types the collector may read (validated by
    // CapabilityAgentService.checkSources). Older documents may also carry empty "network"/"secrets" lists.
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

    // S3 RUNNER platform.
    // The paired runner (relay Device id) the collector is installed on, and its id there ("kpi-<kpiId>", same
    // across versions: a repair re-installs under the same id).
    private String runnerDeviceId;
    private String collectorId;
    // Generated bundle as one JSON string {manifest, files} (file paths contain dots, which Mongo map keys can't).
    private String collectorBundle;
    // contractHash = the runner's sha256 of {sources, schedule, window} for this version's manifest;
    // approvedContractHash = the hash the user approved (copied to repair versions: same hash = no new approval).
    private String contractHash;
    private String approvedContractHash;
    // Last fixture test run on the runner (per case), shown in the UI.
    private List<CollectorTestCase> collectorTests;
    // Readings pull bookkeeping: max recordedAt seen (ISO, the runner's ?since=), the day of the last pull,
    // and whether POST /activate succeeded.
    private String readingsSince;
    private LocalDate lastPulledOn;
    private Boolean collectorActivated;

    public record CollectorTestCase(String name, boolean passed, Double expected, Double got, String error) {}

    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private String lastResult;
}
