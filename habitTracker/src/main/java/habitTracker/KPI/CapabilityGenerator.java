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
     * sources: the source types the collector will read ("activitywatch", "file", "http"); empty = the agent found no
     * source it can use, which CapabilityAgentService rejects.
     * credentials: for "http", the runner credentials (name + host) the collector needs; one the runner does not hold
     * yet makes CapabilityAgentService reject the plan with "add a credential named X for host Y".
     */
    record Proposal(String proposal, String measures, String dataSource, List<String> sources,
                    List<CredentialRef> credentials) {
        public Proposal {
            credentials = credentials == null ? List.of() : List.copyOf(credentials);
        }

        public Proposal(String proposal, String measures, String dataSource, List<String> sources) {
            this(proposal, measures, dataSource, sources, List.of());
        }

        public Proposal(String proposal, List<String> sources) {
            this(proposal, null, null, sources, List.of());
        }
    }

    /** True when userId has a runner online right now (cheap, no LLM call). */
    boolean runnerAvailable(String userId);

    /** available = the credentials (name + host, never values) on the runner the collector will be installed on. */
    Proposal propose(String userId, String kpiName, String description, List<CredentialRef> available);

    /**
     * A collector bundle (manifest + collector.py + fixtures). previousBundleJson/previousError are non-null when
     * seeding a retry or repair with a failure. credentials = the approved credentials (name + host) an "http" source
     * may use; empty when the plan reads none.
     */
    CollectorBundle generateCollector(String userId, String kpiName, String description, String proposal,
                                      List<String> sources, List<CredentialRef> credentials,
                                      String previousBundleJson, String previousError);
}
