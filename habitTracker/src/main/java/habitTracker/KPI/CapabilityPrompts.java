package habitTracker.KPI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Java port of backup/claude_cli.py build_capability_prompt + parse_capability_result (M8 capability agent).
 * Prompt text lives in resources/prompts/kpi-capability-*.md with {{PLACEHOLDER}}s, filled in ONE pass so
 * user text containing "{{...}}" is never re-expanded. Model output is untrusted data: parse rejects anything
 * that is not the expected JSON object.
 */
@Component
public class CapabilityPrompts {

    static final int MAX_SOURCE_BYTES = 64 * 1024; // sandbox's own source cap; fail early instead of at the sidecar
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final ObjectMapper json;
    private final String sandboxFacts;
    private final String proposeTemplate;
    private final String generateTemplate;
    private final String previousFailureTemplate;

    public CapabilityPrompts(ObjectMapper json) {
        this.json = json;
        this.sandboxFacts = load("kpi-capability-sandbox-facts.md").strip();
        this.proposeTemplate = load("kpi-capability-propose.md").stripTrailing();
        this.generateTemplate = load("kpi-capability-generate.md").stripTrailing();
        this.previousFailureTemplate = load("kpi-capability-previous-failure.md");
    }

    public String proposePrompt(String kpiName, String description) {
        return fill(proposeTemplate, Map.of("KPI_NAME", kpiName, "DESCRIPTION", description, "SANDBOX_FACTS", sandboxFacts));
    }

    public String generatePrompt(String kpiName, String description, String proposal,
                                 Map<String, List<String>> declared, String previousCode, String previousError) {
        String declaredJson;
        try {
            declaredJson = json.writeValueAsString(declared == null ? Map.of() : declared);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("declaredCapabilities not serialisable", e);
        }
        String previous = previousError == null || previousError.isEmpty() ? ""
                : fill(previousFailureTemplate, Map.of(
                        "PREVIOUS_CODE", previousCode == null || previousCode.isEmpty() ? "(none)" : previousCode,
                        "PREVIOUS_ERROR", previousError));
        Map<String, String> vars = new HashMap<>();
        vars.put("KPI_NAME", kpiName);
        vars.put("DESCRIPTION", description);
        vars.put("PROPOSAL", String.valueOf(proposal));
        vars.put("DECLARED", declaredJson);
        vars.put("SANDBOX_FACTS", sandboxFacts);
        vars.put("PREVIOUS_FAILURE", previous);
        return fill(generateTemplate, vars);
    }

    /** Agent's final message → Proposal. Throws CapabilityGeneratorException (not busy) on anything unusable. */
    public CapabilityGenerator.Proposal parseProposal(JsonNode result) {
        JsonNode out = extractObject(result);
        JsonNode proposal = out.get("proposal");
        if (proposal == null || !proposal.isTextual() || proposal.asText().isBlank()) {
            throw bad("result missing 'proposal'");
        }
        String platform = out.hasNonNull("platform") ? out.get("platform").asText().toUpperCase(Locale.ROOT) : "SERVER";
        JsonNode declared = out.get("declaredCapabilities");
        if (declared != null && !declared.isNull() && !declared.isObject()) {
            throw bad("result had invalid 'declaredCapabilities'");
        }
        Map<String, List<String>> norm = new LinkedHashMap<>();
        for (String key : List.of("network", "secrets")) {
            JsonNode vals = declared == null ? null : declared.get(key);
            List<String> list = new ArrayList<>();
            if (vals != null && !vals.isNull()) {
                if (!vals.isArray()) throw bad("result had invalid declaredCapabilities." + key);
                vals.forEach(v -> list.add(v.isTextual() ? v.asText() : v.toString()));
            }
            norm.put(key, list);
        }
        return new CapabilityGenerator.Proposal(proposal.asText().strip(), platform, norm,
                optionalText(out, "measures"), optionalText(out, "dataSource"));
    }

    // Optional one-line summaries for the UI; absent / non-text / blank → null (the UI falls back).
    private static String optionalText(JsonNode out, String field) {
        JsonNode n = out.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) return null;
        String s = n.asText().strip();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /** Agent's final message → python source. */
    public String parseSourceCode(JsonNode result) {
        JsonNode code = extractObject(result).get("sourceCode");
        if (code == null || !code.isTextual() || code.asText().isBlank()) throw bad("result missing 'sourceCode'");
        String s = code.asText().strip();
        if (s.startsWith("```")) { // model put fences inside the JSON string anyway
            s = stripChar(s, '`');
            if (s.startsWith("python")) s = s.substring("python".length());
            s = s.strip();
        }
        if (s.getBytes(UTF_8).length > MAX_SOURCE_BYTES) throw bad("generated sourceCode exceeds 64KB");
        return s;
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
