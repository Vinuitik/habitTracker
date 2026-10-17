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

    record Proposal(String proposal, String platform, Map<String, List<String>> declaredCapabilities) {}

    Proposal propose(String kpiName, String description);

    /** previousCode/previousError are non-null when seeding a retry or repair with a failure. */
    String generate(String kpiName, String description, String proposal,
                    Map<String, List<String>> declaredCapabilities, String previousCode, String previousError);
}
