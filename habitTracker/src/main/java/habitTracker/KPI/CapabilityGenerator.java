package habitTracker.KPI;

import java.util.List;

/**
 * Java-side seam to the LLM. Implementation: RunnerCapabilityGenerator (a "kpi" job on the KPI owner's own
 * paired Relay runner; the server never spends its own Claude credits). Mocked in tests. Throws
 * CapabilityGeneratorException on any failure (isNoRunner() when no runner of userId is online). Output is
 * untrusted data: a proposal the user must approve and a collector bundle that only ever runs in the runner's
 * own sandbox. Every capability is a RUNNER collector; there is no platform choice.
 */
public interface CapabilityGenerator {

    /**
     * measures / dataSource: optional one-sentence plain-language summaries for the UI (null when omitted).
     * sources: the local source types the collector will read ("activitywatch", "file"); empty = the agent found no
     * local source (e.g. the data lives only on the internet), which CapabilityAgentService rejects.
     */
    record Proposal(String proposal, String measures, String dataSource, List<String> sources) {
        public Proposal(String proposal, List<String> sources) {
            this(proposal, null, null, sources);
        }
    }

    /** True when userId has a runner online right now (cheap, no LLM call). */
    boolean runnerAvailable(String userId);

    Proposal propose(String userId, String kpiName, String description);

    /**
     * A collector bundle (manifest + collector.py + fixtures). previousBundleJson/previousError are non-null when
     * seeding a retry or repair with a failure.
     */
    CollectorBundle generateCollector(String userId, String kpiName, String description, String proposal,
                                      List<String> sources, String previousBundleJson, String previousError);
}
