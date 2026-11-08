package habitTracker.relay;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises "the Claude subscription on the runner is used up" in runner output, so callers can show one
 * plain message instead of a raw/empty runner error. Phrases are Claude CLI wording; generic "rate limit"
 * is deliberately NOT matched (agents talk about third-party API rate limits in normal output).
 * To add a phrase: PHRASES. Wording: MESSAGE.
 */
public final class UsageLimit {

    public static final String CODE = "usage_exhausted";
    public static final String MESSAGE = "Your Claude usage is exhausted. Try again once it resets.";

    private static final Pattern PHRASES = Pattern.compile(
            "usage limit|hit your (usage )?limit|limit reached|out of (extra )?usage|credit balance is too low"
                    + "|rate_limit_error|usage[ _-]?exhausted|quota (exceeded|exhausted)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESETS = Pattern.compile("resets? (?:at )?([^\\n.|·∙\"]{1,40})", Pattern.CASE_INSENSITIVE);
    /** Only the tail of the event log: a limit hit is what ends the job. */
    private static final int TAIL_EVENTS = 5;

    private UsageLimit() {}

    public static boolean matches(String text) {
        return text != null && PHRASES.matcher(text).find();
    }

    /** Text → user message ("… resets 3pm" appended when the runner said when), else empty. */
    public static Optional<String> message(String text) {
        if (!matches(text)) return Optional.empty();
        Matcher r = RESETS.matcher(text);
        return Optional.of(r.find()
                ? "Your Claude usage is exhausted (resets " + r.group(1).strip() + "). Try again then."
                : MESSAGE);
    }

    /** Failed job snapshot {error?, result?, events[]} → user message if it failed on the usage limit. */
    public static Optional<String> message(JsonNode snap) {
        if (snap == null) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        if (snap.path("error").isTextual()) sb.append(snap.path("error").asText()).append('\n');
        if (snap.path("errorKind").isTextual()) sb.append(snap.path("errorKind").asText()).append('\n');
        if (snap.path("result").isTextual()) sb.append(snap.path("result").asText()).append('\n');
        JsonNode events = snap.path("events");
        if (events.isArray()) {
            for (int i = Math.max(0, events.size() - TAIL_EVENTS); i < events.size(); i++) {
                sb.append(events.get(i).path("text").asText("")).append('\n');
            }
        }
        return message(sb.toString());
    }
}
