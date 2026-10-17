package habitTracker.KPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a capability may be GRANTED: validates the model-declared network hosts and secret names.
 * Approval of a proposal = the grant, so this is the gate. Hostnames only: exact names, no IPs, no
 * ports, no wildcards, nothing internal. (The egress proxy re-checks every resolved IP; this is the
 * first line, not the only one.)
 */
public final class CapabilityGrants {

    public static final int MAX_HOSTS = 10;
    public static final int MAX_SECRETS = 10;
    public static final int MIN_SECRET_VALUE_CHARS = 4;
    public static final int MAX_SECRET_VALUE_CHARS = 4096;

    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern SECRET_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private static final Set<String> INTERNAL_NAMES = Set.of(
            "localhost", "sandbox", "javaapp", "mongodbhabit", "mongo-backup", "egress-proxy", "caddy", "cloudflared");
    private static final List<String> INTERNAL_SUFFIXES = List.of(
            ".internal", ".local", ".localhost", ".localdomain", ".lan", ".home", ".corp", ".intranet", ".private",
            ".test", ".invalid", ".example", ".arpa", ".onion");
    // Must match sandbox/app.py: names that would shadow proxy wiring or interpreter/loader behaviour.
    private static final List<String> RESERVED_PREFIXES = List.of(
            "HTTP", "NO_PROXY", "ALL_PROXY", "PYTHON", "LD_", "SSL_", "REQUESTS_", "CURL_");
    private static final Set<String> RESERVED_NAMES = Set.of("PATH", "HOME", "USER", "TZ", "LANG", "TARGET_DATE");

    private CapabilityGrants() {}

    /** Validates and returns a normalized copy ({network: lowercase deduped, secrets: deduped}). */
    public static Map<String, List<String>> normalize(Map<String, List<String>> declared) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> hosts = new ArrayList<>();
        for (String h : listOf(declared, "network")) {
            hosts.add(requireValidHost(h));
        }
        List<String> secrets = new ArrayList<>(new LinkedHashSet<>(listOf(declared, "secrets")));
        for (String s : secrets) {
            if (!isValidSecretName(s)) {
                throw new CapabilityRejectedException("Invalid secret name '" + s
                        + "': use letters, digits and underscores (not starting with a digit), and not a reserved "
                        + "name such as PATH or anything starting with HTTP, PYTHON, LD_ or SSL_.");
            }
        }
        hosts = new ArrayList<>(new LinkedHashSet<>(hosts));
        if (hosts.size() > MAX_HOSTS) {
            throw new CapabilityRejectedException("Too many network hosts declared (max " + MAX_HOSTS + ").");
        }
        if (secrets.size() > MAX_SECRETS) {
            throw new CapabilityRejectedException("Too many secrets declared (max " + MAX_SECRETS + ").");
        }
        out.put("network", hosts);
        out.put("secrets", secrets);
        return out;
    }

    public static List<String> hosts(Map<String, List<String>> declared) {
        return declared == null ? List.of() : listOf(declared, "network");
    }

    public static List<String> secrets(Map<String, List<String>> declared) {
        return declared == null ? List.of() : listOf(declared, "secrets");
    }

    public static boolean isValidSecretName(String name) {
        if (name == null || !SECRET_NAME.matcher(name).matches() || RESERVED_NAMES.contains(name)) return false;
        String up = name.toUpperCase(Locale.ROOT);
        return RESERVED_PREFIXES.stream().noneMatch(up::startsWith);
    }

    /** Throws IllegalArgumentException (HTTP 400) if the value cannot be passed as a single-line env var. */
    public static void requireValidSecretValue(String value) {
        if (value == null || value.length() < MIN_SECRET_VALUE_CHARS || value.length() > MAX_SECRET_VALUE_CHARS) {
            throw new IllegalArgumentException("Secret value must be " + MIN_SECRET_VALUE_CHARS + "-"
                    + MAX_SECRET_VALUE_CHARS + " characters");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Secret value must be a single line");
        }
    }

    private static String requireValidHost(String raw) {
        String h = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String why = hostProblem(h);
        if (why != null) {
            throw new CapabilityRejectedException("Network host '" + raw + "' cannot be granted: " + why
                    + ". Declare plain public hostnames only (e.g. api.example.com).");
        }
        return h;
    }

    private static String hostProblem(String h) {
        if (h.isEmpty()) return "empty";
        if (h.contains("*")) return "wildcards are not allowed";
        if (h.contains(":")) return "ports and IPv6 literals are not allowed";
        if (h.contains("/") || h.contains("@")) return "must be a bare hostname";
        if (!HOST.matcher(h).matches()) return "not a valid fully-qualified hostname (IP addresses and single-label names are not allowed)";
        if (INTERNAL_NAMES.contains(h)) return "internal name";
        for (String suffix : INTERNAL_SUFFIXES) {
            if (h.endsWith(suffix)) return "internal domain";
        }
        return null;
    }

    private static List<String> listOf(Map<String, List<String>> m, String key) {
        List<String> l = m == null ? null : m.get(key);
        return l == null ? List.of() : l;
    }
}
