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

    record Proposal(String proposal, String platform, Map<String, List<String>> declaredCapabilities) {}

    /** True when userId has a runner online right now (cheap, no LLM call). */
    boolean runnerAvailable(String userId);

    Proposal propose(String userId, String kpiName, String description);

    /** previousCode/previousError are non-null when seeding a retry or repair with a failure. */
    String generate(String userId, String kpiName, String description, String proposal,
                    Map<String, List<String>> declaredCapabilities, String previousCode, String previousError);
}
