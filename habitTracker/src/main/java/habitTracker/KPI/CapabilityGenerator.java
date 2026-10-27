package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/**
 * Java-side seam to the LLM (Claude CLI in the mongo-backup container, via its internal API
 * POST /internal/agent/capability). Mocked in tests. Throws CapabilityGeneratorException on any
 * upstream failure. Output is untrusted data: a proposal the user must approve and source code
 * that only ever runs in the sandbox.
 */
public interface CapabilityGenerator {

    /** reply = the agent's short message to the user (may be null); options = 1..n alternatives. */
    record Proposal(String reply, List<CapabilityOption> options) {
        /** Single-option proposal (tests, legacy upstream reply). */
        public Proposal(String summary, String platform, Map<String, List<String>> declaredCapabilities) {
            this(null, List.of(CapabilityOption.of(summary, platform, declaredCapabilities)));
        }
    }

    /**
     * conversation/previousOptions are empty on the first proposal; on a refinement the conversation ends
     * with the user's latest message and previousOptions are the options it reacts to.
     */
    Proposal propose(String kpiName, String description, List<CapabilityTurn> conversation,
                     List<CapabilityOption> previousOptions);

    /** previousCode/previousError are non-null when seeding a retry or repair with a failure. */
    String generate(String kpiName, String description, String proposal,
                    Map<String, List<String>> declaredCapabilities, String previousCode, String previousError);
}
