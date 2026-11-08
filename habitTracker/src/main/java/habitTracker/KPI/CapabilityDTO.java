package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/** API view of a KPIProxyCapability. sourceCode is included so the user can audit what will run. */
public record CapabilityDTO(
        String id,
        String kpiId,
        int version,
        CapabilityStatus status,
        String description,
        String proposal,
        String measures,
        String dataSource,
        CapabilityPlatform platform,
        Map<String, List<String>> declaredCapabilities,
        String sourceCode,
        int attempts,
        int maxAttempts,
        String failureReason,
        String lastResult,
        // S1 confirm gate / shadow: yesterday's sample for AWAITING_CONFIRM (pre-formatted text + structured rows)
        Double sampleValue,
        String sampleUnit,
        String sampleText,
        String sampleDate,
        List<CapabilityOutput.Row> sampleEvidence,
        int confirmRounds,
        int maxConfirmRounds,
        String shadowUntil,
        // S3 RUNNER: the data-access contract in plain language + per-case fixture results (null / empty when none)
        String contractText,
        List<KPIProxyCapability.CollectorTestCase> collectorTests,
        boolean repair,
        // Refine loop (nullable/additive): plan revision (1 = fresh propose), the AI's assumptions + open questions,
        // one sentence on what changed vs the previous plan, and the feedback that produced this revision.
        int planRevision,
        List<String> assumptions,
        String changeSummary,
        String refineFeedback) {

    /** Pre-S1 shape (no sample / confirm fields). */
    public CapabilityDTO(String id, String kpiId, int version, CapabilityStatus status, String description,
                         String proposal, String measures, String dataSource, CapabilityPlatform platform,
                         Map<String, List<String>> declaredCapabilities, String sourceCode, int attempts,
                         int maxAttempts, String failureReason, String lastResult) {
        this(id, kpiId, version, status, description, proposal, measures, dataSource, platform, declaredCapabilities,
                sourceCode, attempts, maxAttempts, failureReason, lastResult,
                null, null, null, null, List.of(), 0, CapabilityAgentService.MAX_CONFIRM_ROUNDS, null, null, List.of(), false, 1, List.of(), null, null);
    }

    static CapabilityDTO from(KPIProxyCapability c) {
        return new CapabilityDTO(c.getId(), c.getKpiId(), c.getVersion() == null ? 1 : c.getVersion(),
                c.getStatus(), c.getDescription(), c.getProposal(), c.getMeasures(), c.getDataSource(), c.getPlatform(), c.getDeclaredCapabilities(),
                c.getSourceCode(), c.getAttempts() == null ? 0 : c.getAttempts(),
                CapabilityAgentService.MAX_ATTEMPTS, c.getFailureReason(), c.getLastResult(),
                c.getSampleValue(), c.getSampleUnit(),
                c.getSampleValue() == null ? null : CapabilityOutput.formatValue(c.getSampleValue(), c.getSampleUnit()),
                c.getSampleDate() == null ? null : c.getSampleDate().toString(),
                c.getSampleEvidence() == null ? List.of() : c.getSampleEvidence(),
                c.getConfirmRounds() == null ? 0 : c.getConfirmRounds(), CapabilityAgentService.MAX_CONFIRM_ROUNDS,
                c.getShadowUntil() == null ? null : c.getShadowUntil().toString(),
                contractText(c),
                c.getCollectorTests() == null ? List.of() : c.getCollectorTests(),
                Boolean.TRUE.equals(c.getRepair()),
                c.getPlanRevision() == null ? 1 : c.getPlanRevision(), c.getAssumptions() == null ? List.of() : c.getAssumptions(),
                c.getChangeSummary(), c.getRefineFeedback());
    }

    private static String contractText(KPIProxyCapability c) {
        if (c.getPlatform() != CapabilityPlatform.RUNNER) return null;
        CollectorBundle b = CollectorBundle.fromJson(c.getCollectorBundle());
        return b == null ? null : CollectorStaticCheck.contractText(b.manifest());
    }
}
