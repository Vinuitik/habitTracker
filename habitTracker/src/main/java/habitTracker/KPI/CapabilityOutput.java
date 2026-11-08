package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * What a capability script prints on its last non-blank stdout line. Two accepted shapes (platform-agnostic:
 * RUNNER collector stdout):
 *   1. the old plain number            47
 *   2. a JSON object                   {"value": 47, "unit": "min", "evidence": [{"label": "...", "value": "..."}]}
 * Evidence is capped at MAX_EVIDENCE rows. Anything else (or a non-finite value) parses to null.
 */
public record CapabilityOutput(double value, String unit, List<Row> evidence) {

    public static final int MAX_EVIDENCE = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Row(String label, String value) {}

    public static CapabilityOutput parse(String stdout) {
        if (stdout == null) return null;
        String last = null;
        for (String line : stdout.split("\\R", -1)) {
            if (!line.isBlank()) last = line.trim();
        }
        if (last == null) return null;
        if (last.startsWith("{") || last.endsWith("}")) {
            CapabilityOutput o = parseJson(last);
            if (o == null) { // pretty-printed object: from the last line that opens at column 0 to the end
                int open = stdout.lastIndexOf("\n{");
                if (open >= 0) o = parseJson(stdout.substring(open + 1).trim());
            }
            return o;
        }
        try {
            double d = Double.parseDouble(last);
            return Double.isFinite(d) ? new CapabilityOutput(d, null, List.of()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static CapabilityOutput parseJson(String text) {
        try {
            JsonNode n = JSON.readTree(text);
            if (n == null || !n.isObject()) return null;
            JsonNode v = n.get("value");
            if (v == null) return null;
            double d;
            if (v.isNumber()) d = v.asDouble();
            else if (v.isTextual()) d = Double.parseDouble(v.asText().trim());
            else return null;
            if (!Double.isFinite(d)) return null;
            String unit = n.hasNonNull("unit") && n.get("unit").isTextual() && !n.get("unit").asText().isBlank()
                    ? clip(n.get("unit").asText(), 20) : null;
            List<Row> rows = new ArrayList<>();
            JsonNode ev = n.get("evidence");
            if (ev != null && ev.isArray()) {
                for (JsonNode r : ev) {
                    if (rows.size() >= MAX_EVIDENCE) break;
                    if (!r.isObject() || !r.hasNonNull("label")) continue;
                    JsonNode rv = r.get("value");
                    String sv = rv == null || rv.isNull() ? "" : rv.isValueNode() ? rv.asText() : rv.toString();
                    rows.add(new Row(clip(r.get("label").asText(), 80), clip(sv, 120)));
                }
            }
            return new CapabilityOutput(d, unit, List.copyOf(rows));
        } catch (Exception e) { // bad JSON, NumberFormatException, ...
            return null;
        }
    }

    /** "47" / "47.5" (no trailing .0) plus the unit when there is one. */
    public static String formatValue(double v, String unit) {
        String s = v == Math.rint(v) && Math.abs(v) < 1e15
                ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
        return unit == null || unit.isBlank() ? s : s + " " + unit;
    }

    private static String clip(String s, int max) {
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
