package habitTracker.KPI;

import java.util.List;
import java.util.Map;

/**
 * One alternative the agent offers for capturing a KPI. Everything except blockedReason is LLM output
 * (untrusted, rendered as text only). cost / method / difficulty are normalized labels (see
 * backup/claude_cli.py OPTION_ENUMS); sources are the pages the agent's web research relied on, empty =
 * unverified. blockedReason is set by Java when the sandbox cannot grant the option: it is shown but can't
 * be approved.
 */
public record CapabilityOption(
        String title,
        String summary,
        String platform,
        Map<String, List<String>> declaredCapabilities,
        String cost,           // FREE | FREE_TIER | PAID | UNKNOWN
        String costNote,
        String access,         // what the user must create or hand over (account, API key, ...)
        String method,         // OFFICIAL_API | PUBLIC_DATA | SCRAPING | UNOFFICIAL_API | DEVICE_ONLY | UNKNOWN
        String difficulty,     // LOW | MEDIUM | HIGH | UNKNOWN: how hard to obtain + how likely to break
        String difficultyNote,
        List<String> sources,
        String blockedReason) {

    /** Bare option (legacy single-proposal upstream reply, tests). */
    static CapabilityOption of(String summary, String platform, Map<String, List<String>> declared) {
        return new CapabilityOption(null, summary, platform, declared, "UNKNOWN", null, null, "UNKNOWN",
                "UNKNOWN", null, List.of(), null);
    }

    CapabilityOption vetted(Map<String, List<String>> declared, String blockedReason) {
        return new CapabilityOption(title, summary, platform, declared, cost, costNote, access, method,
                difficulty, difficultyNote, sources, blockedReason);
    }
}
