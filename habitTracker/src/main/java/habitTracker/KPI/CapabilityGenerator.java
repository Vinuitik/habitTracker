package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/**
 * Java-side seam to the LLM. Implementation: RunnerCapabilityGenerator (a "kpi" job on the KPI owner's own
 * paired Relay runner; the server never spends its own Claude credits). Mocked in tests. Throws
 * CapabilityGeneratorException on any failure (isNoRunner() when no runner of userId is online). Output is
 * untrusted data: a proposal the user must approve and source code that only ever runs in the sandbox.
 */
public interface CapabilityGenerator {

    /** measures / dataSource: optional one-sentence plain-language summaries for the UI (null when omitted). */
    /** sources: RUNNER only, the local source types the collector will read ("activitywatch", "file"). */
    record Proposal(String proposal, String platform, Map<String, List<String>> declaredCapabilities,
                    String measures, String dataSource, List<String> sources) {
        public Proposal(String proposal, String platform, Map<String, List<String>> declaredCapabilities) {
            this(proposal, platform, declaredCapabilities, null, null, List.of());
        }

        public Proposal(String proposal, String platform, Map<String, List<String>> declaredCapabilities,
                        String measures, String dataSource) {
            this(proposal, platform, declaredCapabilities, measures, dataSource, List.of());
        }
    }

    /** True when userId has a runner online right now (cheap, no LLM call). */
    boolean runnerAvailable(String userId);

    Proposal propose(String userId, String kpiName, String description);

    /** previousCode/previousError are non-null when seeding a retry or repair with a failure. */
    String generate(String userId, String kpiName, String description, String proposal,
                    Map<String, List<String>> declaredCapabilities, String previousCode, String previousError);

    /**
     * S3 RUNNER: a collector bundle (manifest + collector.py + fixtures). previousBundleJson/previousError are
     * non-null when seeding a retry or repair with a failure.
     */
    CollectorBundle generateCollector(String userId, String kpiName, String description, String proposal,
                                      List<String> sources, String previousBundleJson, String previousError);
}
