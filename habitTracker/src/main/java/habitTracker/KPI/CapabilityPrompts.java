package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Prompts of the capability agent (M8, runner-only since runner-only-kpis.md H1): propose a plan, then write a
 * RUNNER collector bundle. Prompt text lives in resources/prompts/kpi-*.md with {{PLACEHOLDER}}s, filled in ONE pass so
 * user text containing "{{...}}" is never re-expanded. Model output is untrusted data: parse rejects anything
 * that is not the expected JSON object.
 */
@Component
public class CapabilityPrompts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final ObjectMapper json;
    private final String proposeTemplate;
    private final String previousFailureTemplate;

    public CapabilityPrompts(ObjectMapper json) {
        this.json = json;
        this.proposeTemplate = load("kpi-capability-propose.md").stripTrailing();
        this.previousFailureTemplate = load("kpi-capability-previous-failure.md");
        this.collectorTemplate = load("kpi-collector-generate.md").stripTrailing();
    }

    private final String collectorTemplate;

    /** S3 RUNNER collector bundle prompt (kpi-collector-generate.md). */
    public String collectorPrompt(String kpiName, String description, String proposal, List<String> sources,
                                  List<CredentialRef> credentials, String previousBundleJson, String previousError) {
        String previous = previousError == null || previousError.isEmpty() ? ""
                : fill(previousFailureTemplate, Map.of(
                        "PREVIOUS_CODE", previousBundleJson == null || previousBundleJson.isEmpty() ? "(none)" : previousBundleJson,
                        "PREVIOUS_ERROR", previousError));
        Map<String, String> vars = new HashMap<>();
        vars.put("KPI_NAME", kpiName);
        vars.put("DESCRIPTION", description);
        vars.put("PROPOSAL", String.valueOf(proposal));
        vars.put("SOURCES", sources == null || sources.isEmpty() ? "(none declared)" : String.join(", ", sources));
        vars.put("CREDENTIALS", credentialList(credentials, "(none: this collector may not use an http source)"));
        vars.put("PREVIOUS_FAILURE", previous);
        return fill(collectorTemplate, vars);
    }

    /** Agent's final message → collector bundle (shape only; CollectorStaticCheck validates the content). */
    public CollectorBundle parseCollectorBundle(JsonNode result) {
        try {
            return CollectorBundle.of(extractObject(result));
        } catch (IllegalArgumentException e) {
            throw bad("collector bundle: " + e.getMessage());
        }
    }

    /** available = the runner's credentials, names + hosts only (never values: the hub never has them). */
    public String proposePrompt(String kpiName, String description, List<CredentialRef> available) {
        return fill(proposeTemplate, Map.of("KPI_NAME", kpiName, "DESCRIPTION", description,
                "CREDENTIALS", credentialList(available, "(none yet)")));
    }

    /** "- github (host api.github.com)" lines. Names/hosts come from the runner; re-filtered here anyway. */
    static String credentialList(List<CredentialRef> refs, String none) {
        if (refs == null || refs.isEmpty()) return none;
        StringBuilder sb = new StringBuilder();
        for (CredentialRef r : refs) {
            if (!r.valid()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("- ").append(r.name()).append(" (host ").append(r.host()).append(')');
        }
        return sb.isEmpty() ? none : sb.toString();
    }

    /** Agent's final message → Proposal. Throws CapabilityGeneratorException (not busy) on anything unusable. */
    public CapabilityGenerator.Proposal parseProposal(JsonNode result) {
        JsonNode out = extractObject(result);
        JsonNode proposal = out.get("proposal");
        if (proposal == null || !proposal.isTextual() || proposal.asText().isBlank()) {
            throw bad("result missing 'proposal'");
        }
        List<String> sources = new ArrayList<>();
        JsonNode src = out.get("sources");
        if (src != null && src.isArray()) {
            src.forEach(v -> {
                String s = v.asText("").strip().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && !sources.contains(s)) sources.add(s);
            });
        }
        List<CredentialRef> credentials = new ArrayList<>();
        JsonNode creds = out.get("credentials");
        if (creds != null && creds.isArray()) {
            creds.forEach(c -> {
                if (!c.isObject()) return;
                CredentialRef r = new CredentialRef(c.path("name").asText(""), c.path("host").asText(""));
                if (!r.name().isEmpty() && credentials.stream().noneMatch(x -> x.name().equals(r.name()))) credentials.add(r);
            });
        }
        return new CapabilityGenerator.Proposal(proposal.asText().strip(),
                optionalText(out, "measures"), optionalText(out, "dataSource"), sources, credentials);
    }

    // Optional one-line summaries for the UI; absent / non-text / blank → null (the UI falls back).
    private static String optionalText(JsonNode out, String field) {
        JsonNode n = out.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) return null;
        String s = n.asText().strip();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    // Runner job result = the agent's final message: a JSON object, or text containing one (fences / prose tolerated).
    private JsonNode extractObject(JsonNode result) {
        if (result == null || result.isNull()) throw bad("job returned no result");
        if (result.isObject()) return result;
        if (!result.isTextual()) throw bad("result was not the expected JSON object");
        String text = result.asText().strip();
        if (text.startsWith("```")) {
            text = stripChar(text, '`');
            if (text.startsWith("json")) text = text.substring(4);
            text = text.strip();
        }
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw bad("result was not the expected JSON object");
        try {
            JsonNode node = json.readTree(text.substring(a, b + 1));
            if (!node.isObject()) throw bad("result was not the expected JSON object");
            return node;
        } catch (IOException e) {
            throw bad("result was not the expected JSON object");
        }
    }

    static String fill(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripChar(String s, char c) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == c) a++;
        while (b > a && s.charAt(b - 1) == c) b--;
        return s.substring(a, b);
    }

    private static CapabilityGeneratorException bad(String msg) {
        return new CapabilityGeneratorException("The AI's reply could not be used (" + msg + "). Try again", false, null);
    }

    private static String load(String name) {
        try {
            return new ClassPathResource("prompts/" + name).getContentAsString(UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt resource " + name, e);
        }
    }
}
