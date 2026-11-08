package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hub-side static check of a RUNNER collector bundle (kpi-sensors.md "Test pipeline" step 2), before it is sent to
 * the runner. Not a security boundary (the runner's sandbox is: no network, read-only declared inputs); it catches
 * bad bundles early and gives the repair loop a precise reason. Checks:
 *   - manifest: schedule "daily HH:MM" + window "1d" (hourly is [NOT IMPLEMENTED] on the hub: the pull writes one
 *     value per day), unit, aggregation sum|last|max, 1-10 sources, only types the approved proposal declared
 *   - activitywatch buckets in {window, web, afk}; file globs absolute or ~/, no "**", not a secret path
 *     (mirror of the runner's sources.go deny-list), not a whole home/root
 *   - http: credential is one the approved plan lists, url https:// on exactly that credential's host, no port /
 *     userinfo / fragment, placeholders only {from}/{to} after the host, method absent or GET, a "why"
 *     (mirror of the runner's install-time url rules, Relay shared/API.md "http source")
 *   - files: only collector.py + fixtures/<case>/{inputs/..., expected.json}, no "..", size caps
 *   - fixtures: ≥ 2 cases, each with an expected.json holding a numeric "value", at least one expecting 0 (empty day)
 *   - collector.py imports: an allowlist of stdlib modules (regex over import lines, no AST); no __import__/importlib/exec
 */
public final class CollectorStaticCheck {

    static final int MAX_FILES = 60;
    static final int MAX_TOTAL_BYTES = 1024 * 1024;
    static final int MAX_SOURCES = 10;
    static final int MAX_SOURCE_BYTES = 64 * 1024; // collector.py cap
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern DAILY = Pattern.compile("daily ([01][0-9]|2[0-3]):([0-5][0-9])");
    private static final Pattern CASE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Set<String> BUCKETS = Set.of("window", "web", "afk");
    static final Set<String> SOURCE_TYPES = Set.of("activitywatch", "file", "http");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}?");
    private static final Pattern IMPORT = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import\\b|import\\s+([\\w.,\\s]+?)(?:\\s+as\\s+\\w+)?\\s*(?:#.*)?$)",
            Pattern.MULTILINE);
    private static final Pattern DYNAMIC = Pattern.compile("\\b(__import__|importlib|exec\\s*\\(|eval\\s*\\(|compile\\s*\\()");

    /** Stdlib modules a collector over local snapshots plausibly needs. Network/process modules are absent on purpose. */
    static final Set<String> ALLOWED_MODULES = Set.of(
            "__future__", "abc", "argparse", "base64", "bisect", "bz2", "calendar", "codecs", "collections", "contextlib",
            "copy", "csv", "dataclasses", "datetime", "decimal", "enum", "fnmatch", "fractions", "functools", "glob",
            "gzip", "hashlib", "heapq", "html", "io", "itertools", "json", "locale", "lzma", "math", "operator", "os",
            "pathlib", "plistlib", "re", "shutil", "sqlite3", "statistics", "string", "struct", "sys", "tarfile",
            "tempfile", "textwrap", "time", "traceback", "typing", "unicodedata", "urllib.parse", "uuid", "warnings",
            "xml", "zipfile", "zlib", "zoneinfo", "configparser", "numbers", "pprint", "random", "difflib", "ast");

    // Mirror of Relay runner/internal/collectors/sources.go deniedDirs / deniedFiles (fail closed).
    private static final List<String> DENIED_DIRS = List.of(".ssh", ".gnupg", ".aws", ".kube", ".password-store",
            "keyrings", "secrets", ".docker");
    private static final List<String> DENIED_FILES = List.of(".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx",
            "id_rsa*", "id_ed25519*", "id_ecdsa*", "id_dsa*", "credentials*.json", ".netrc", ".git-credentials",
            ".pgpass", "cookies", "cookies-journal", "login data", "login data-journal", "login data for account*",
            "web data", "web data-journal", "local state", "key3.db", "key4.db", "logins.json", "cookies.sqlite*",
            "signons.sqlite");

    private CollectorStaticCheck() {}

    /** → problems in plain sentences (empty = passed). allowedSources = source types the user approved in the plan. */
    public static List<String> check(CollectorBundle b, Collection<String> allowedSources) {
        return check(b, allowedSources, Map.of());
    }

    /** credentials = approved credential name → host (CredentialRef.byName of the plan); http sources must use one. */
    public static List<String> check(CollectorBundle b, Collection<String> allowedSources, Map<String, String> credentials) {
        return check(b, allowedSources, credentials, List.of());
    }

    /** One device the collector will run on: its OS (Go runtime.GOOS) and the package managers found there. */
    public record Target(String os, Collection<String> managers) {}

    /**
     * targets = the devices the collector will run on (empty = unknown: variants are still checked for shape, but not
     * against any device). A legacy bundle (no variants) is accepted only for a single device.
     */
    public static List<String> check(CollectorBundle b, Collection<String> allowedSources, Map<String, String> credentials,
                                     List<Target> targets) {
        List<String> p = new ArrayList<>();
        checkManifest(b.manifest(), allowedSources, credentials == null ? Map.of() : credentials,
                targets == null ? List.of() : targets, p);
        checkFiles(b, p);
        String py = b.collectorPy();
        if (py != null) checkImports(py, p);
        return p;
    }

    private static final Set<String> OSES = Set.of("windows", "linux", "darwin");
    private static final Set<String> COMBINE = Set.of("sum", "max", "avg", "latest");
    static final int MAX_REQUIRES = 5;
    private static final Map<String, Pattern> ID_PATTERNS = Map.of(
            "winget", Pattern.compile("^[A-Za-z0-9.+-]{1,128}$"),
            "brew", Pattern.compile("^[a-z0-9@+._-]{1,64}(/[a-z0-9._-]+){0,2}$"),
            "flatpak", Pattern.compile("^[A-Za-z0-9_.-]{3,128}$"),
            "pip", Pattern.compile("^[A-Za-z0-9._-]{1,64}(==[A-Za-z0-9.*+!-]{1,32})?$"));

    private static void checkManifest(JsonNode m, Collection<String> allowed, Map<String, String> credentials,
                                      List<Target> targets, List<String> p) {
        String schedule = m.path("schedule").asText("");
        String window = m.path("window").asText("");
        if ("hourly".equals(schedule)) {
            p.add("manifest.schedule \"hourly\" is not supported yet: use \"daily HH:MM\" with window \"1d\"");
        } else if (!DAILY.matcher(schedule).matches()) {
            p.add("manifest.schedule must be \"daily HH:MM\" (got \"" + clip(schedule) + "\")");
        }
        if (!"1d".equals(window)) p.add("manifest.window must be \"1d\" (got \"" + clip(window) + "\")");
        if (m.path("unit").asText("").isBlank()) p.add("manifest.unit is required");
        String agg = m.path("aggregation").asText("");
        if (!Set.of("sum", "last", "max").contains(agg)) p.add("manifest.aggregation must be sum, last or max");
        if (m.path("variants").isObject()) {
            checkVariants(m, allowed, credentials, targets, p);
            return;
        }
        if (targets.size() > 1) {
            p.add("The collector must have a variants entry for each operating system it runs on, because it is installed on "
                    + targets.size() + " devices");
        }
        if (m.has("combine") && !COMBINE.contains(m.path("combine").asText(""))) {
            p.add("manifest.combine must be sum, max, avg or latest");
        }
        checkSourceList(m.get("sources"), "", allowed, credentials, p);
        checkRequires(m.get("requires"), "", null, p);
    }

    private static void checkVariants(JsonNode m, Collection<String> allowed, Map<String, String> credentials,
                                      List<Target> targets, List<String> p) {
        if (!COMBINE.contains(m.path("combine").asText(""))) p.add("manifest.combine must be sum, max, avg or latest");
        JsonNode variants = m.get("variants");
        if (variants.isEmpty()) {
            p.add("manifest.variants must have one entry per operating system");
            return;
        }
        for (Target t : targets) {
            if (t.os() != null && !variants.has(t.os())) {
                p.add("The collector has no variant for " + t.os() + ", which one of the target devices runs");
            }
        }
        List<String> firstShape = null;
        String firstOs = null;
        var it = variants.fields();
        while (it.hasNext()) {
            var e = it.next();
            String os = e.getKey();
            JsonNode v = e.getValue();
            String prefix = "variant " + os + ": ";
            if (!OSES.contains(os)) {
                p.add("manifest.variants key \"" + clip(os) + "\" is not windows, linux or darwin");
                continue;
            }
            if (!v.isObject()) {
                p.add(prefix + "a variant must be an object with sources and requires");
                continue;
            }
            if (checkSourceList(v.get("sources"), prefix, allowed, credentials, p)) {
                List<String> shape = new ArrayList<>();
                v.get("sources").forEach(s -> shape.add(s.path("type").asText("")));
                if (firstShape == null) {
                    firstShape = shape;
                    firstOs = os;
                } else if (!firstShape.equals(shape)) {
                    p.add("Variant " + os + " reads " + shape.size() + " sources of types " + shape + " but variant " + firstOs
                            + " reads " + firstShape.size() + " of types " + firstShape
                            + ", and every variant must have the same sources in the same type order");
                }
            }
            checkRequires(v.get("requires"), prefix, managersFor(os, targets), p);
        }
    }

    /** null = no device of that OS is known (managers are not checked); else the managers every such device has. */
    private static Set<String> managersFor(String os, List<Target> targets) {
        Set<String> out = null;
        for (Target t : targets) {
            if (!os.equals(t.os())) continue;
            Set<String> have = t.managers() == null ? Set.of() : new java.util.HashSet<>(t.managers());
            if (out == null) out = have;
            else out.retainAll(have);
        }
        return out;
    }

    private static void checkRequires(JsonNode requires, String prefix, Set<String> managers, List<String> p) {
        if (requires == null || requires.isNull()) return;
        if (!requires.isArray()) {
            p.add(prefix + "requires must be a list");
            return;
        }
        if (requires.size() > MAX_REQUIRES) p.add(prefix + "requires may list at most " + MAX_REQUIRES + " packages");
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode r : requires) {
            String manager = r.path("manager").asText("");
            String id = r.path("id").asText("");
            if (!manager.equals("relay") && !ID_PATTERNS.containsKey(manager)) {
                p.add(prefix + "requires manager \"" + clip(manager) + "\" is not one of relay, winget, brew, flatpak or pip");
                continue;
            }
            if (managers != null && !managers.contains(manager)) {
                p.add(prefix + "requires " + manager + ", which is not available on the " + prefix.substring(8, prefix.length() - 2)
                        + " device (it has " + (managers.isEmpty() ? "no package managers" : String.join(", ", new java.util.TreeSet<>(managers))) + ")");
                continue;
            }
            if (id.startsWith("-")) {
                p.add(prefix + "requires id \"" + clip(id) + "\" may not start with a dash");
            } else if (manager.equals("relay") ? !id.equals("activitywatch") : !ID_PATTERNS.get(manager).matcher(id).matches()) {
                p.add(prefix + "requires id \"" + clip(id) + "\" is not a valid " + manager + " package id");
            } else if (!seen.add(manager + ":" + id)) {
                p.add(prefix + "requires lists " + manager + " " + clip(id) + " twice");
            }
            if (r.path("why").asText("").isBlank()) p.add(prefix + "requires " + manager + " " + clip(id) + " needs a \"why\"");
        }
    }

    /** → true when the list had a usable shape (types can be compared across variants). */
    private static boolean checkSourceList(JsonNode sources, String prefix, Collection<String> allowed,
                                           Map<String, String> credentials, List<String> p) {
        if (sources == null || !sources.isArray() || sources.isEmpty() || sources.size() > MAX_SOURCES) {
            p.add(prefix + "manifest.sources must list 1-" + MAX_SOURCES + " sources");
            return false;
        }
        int i = 0;
        for (JsonNode s : sources) {
            String type = s.path("type").asText("");
            String at = prefix + "source " + i + ": ";
            if (!SOURCE_TYPES.contains(type)) {
                p.add(at + "unknown type \"" + clip(type) + "\" (activitywatch, file or http)");
            } else if (allowed != null && !allowed.contains(type)) {
                p.add(at + "reads " + type + ", which the approved plan did not declare");
            }
            if (type.equals("activitywatch")) {
                JsonNode buckets = s.get("buckets");
                if (buckets == null || !buckets.isArray() || buckets.isEmpty()) {
                    p.add(at + "activitywatch needs buckets");
                } else {
                    buckets.forEach(bk -> {
                        if (!BUCKETS.contains(bk.asText())) p.add("activitywatch bucket \"" + clip(bk.asText()) + "\" is not one of window, web, afk");
                    });
                }
            } else if (type.equals("file")) {
                String why = globProblem(s.path("glob").asText(""));
                if (why != null) p.add(at + why);
                if (s.path("why").asText("").isBlank()) p.add(at + "a file source needs \"why\"");
            } else if (type.equals("http")) {
                String why = httpProblem(s, credentials);
                if (why != null) p.add(at + why);
            }
            i++;
        }
        return true;
    }

    /** null when the http source is acceptable, else why not. credentials = approved name → host. */
    static String httpProblem(JsonNode s, Map<String, String> credentials) {
        String name = s.path("credential").asText("");
        String url = s.path("url").asText("");
        String method = s.path("method").asText("GET");
        if (!"GET".equals(method)) return "an http source may only GET (got \"" + clip(method) + "\")";
        if (s.path("why").asText("").isBlank()) return "an http source needs \"why\"";
        if (name.isBlank()) return "an http source needs \"credential\"";
        String host = credentials.get(name);
        if (host == null) {
            return "credential \"" + clip(name) + "\" is not one the approved plan uses"
                    + (credentials.isEmpty() ? " (the plan uses none)" : " (" + String.join(", ", credentials.keySet()) + ")");
        }
        if (!url.startsWith("https://")) return "url \"" + clip(url) + "\" must start with https://";
        String rest = url.substring("https://".length());
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) end = k;
        }
        String authority = rest.substring(0, end);
        if (authority.contains("@")) return "url \"" + clip(url) + "\" may not contain user:password@";
        if (authority.contains(":")) return "url \"" + clip(url) + "\" may not set a port";
        if (authority.contains("{")) return "url \"" + clip(url) + "\": placeholders may only come after the host";
        if (!authority.toLowerCase(Locale.ROOT).equals(host)) {
            return "url host \"" + clip(authority) + "\" must be the credential's host " + host;
        }
        if (url.contains("#")) return "url \"" + clip(url) + "\" may not have a #fragment";
        Matcher ph = PLACEHOLDER.matcher(rest);
        while (ph.find()) {
            if (!ph.group().equals("{from}") && !ph.group().equals("{to}")) {
                return "url placeholder " + clip(ph.group()) + " is not supported (only {from} and {to})";
            }
        }
        if (url.chars().anyMatch(c -> c <= ' ' || c == 0x7f)) return "url may not contain spaces or control characters";
        return null;
    }

    /** null when the glob is acceptable, else why not. */
    static String globProblem(String g) {
        if (g.isBlank()) return "a file source needs a glob";
        if (g.contains("**")) return "glob \"" + clip(g) + "\": \"**\" is not supported";
        if (!g.startsWith("~/") && !g.startsWith("/") && !g.matches("^[A-Za-z]:[\\\\/].*")) {
            return "glob \"" + clip(g) + "\" must be absolute or start with ~/";
        }
        String[] parts = g.split("[/\\\\]+");
        List<String> nonEmpty = new ArrayList<>();
        for (String s : parts) if (!s.isEmpty() && !s.equals("~")) nonEmpty.add(s);
        if (nonEmpty.size() < 2 || (nonEmpty.size() == 2 && nonEmpty.get(1).equals("*") && g.startsWith("~/"))) {
            return "glob \"" + clip(g) + "\" is too broad: name the app's folder";
        }
        for (String s : nonEmpty) if (s.equals("..")) return "glob \"" + clip(g) + "\" may not contain \"..\"";
        for (int i = 0; i < nonEmpty.size(); i++) {
            String low = nonEmpty.get(i).toLowerCase(Locale.ROOT);
            if (DENIED_DIRS.contains(low)) return "glob \"" + clip(g) + "\" reads a secret path (" + low + ")";
        }
        String base = nonEmpty.get(nonEmpty.size() - 1).toLowerCase(Locale.ROOT);
        for (String pat : DENIED_FILES) {
            if (globMatch(pat, base) || globMatch(base, pat)) return "glob \"" + clip(g) + "\" reads a secret path (" + pat + ")";
        }
        return null;
    }

    private static void checkFiles(CollectorBundle b, List<String> p) {
        if (b.collectorPy() == null || b.collectorPy().isBlank()) p.add("files must contain collector.py");
        if (b.files().size() > MAX_FILES) p.add("too many files (max " + MAX_FILES + ")");
        long total = 0;
        for (var e : b.files().entrySet()) {
            String path = e.getKey();
            total += e.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (path.equals("collector.py")) continue;
            String[] parts = path.split("/");
            boolean ok = parts.length >= 3 && parts[0].equals("fixtures") && CASE.matcher(parts[1]).matches()
                    && (path.equals("fixtures/" + parts[1] + "/expected.json")
                    || (parts.length >= 4 && parts[2].equals("inputs")));
            for (String s : parts) if (s.isEmpty() || s.equals("..") || s.equals(".")) ok = false;
            if (!ok) p.add("unexpected file \"" + clip(path) + "\" (only collector.py and fixtures/<case>/inputs/... + expected.json)");
        }
        if (total > MAX_TOTAL_BYTES) p.add("bundle is larger than 1 MB");
        if (b.collectorPy() != null && b.collectorPy().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            p.add("collector.py exceeds 64 KB");
        }
        List<String> cases = b.fixtureCases();
        if (cases.size() < 2) p.add("needs at least 2 fixture cases (one of them an empty day that gives 0)");
        boolean zeroCase = false;
        for (String c : cases) {
            String exp = b.files().get("fixtures/" + c + "/expected.json");
            if (exp == null) {
                p.add("fixture \"" + clip(c) + "\" has no expected.json");
                continue;
            }
            try {
                JsonNode v = JSON.readTree(exp).get("value");
                if (v == null || !v.isNumber()) p.add("fixture \"" + clip(c) + "\": expected.json needs a numeric \"value\"");
                else if (v.asDouble() == 0) zeroCase = true;
            } catch (Exception e) {
                p.add("fixture \"" + clip(c) + "\": expected.json is not valid JSON");
            }
        }
        if (cases.size() >= 2 && !zeroCase) p.add("one fixture must be an empty day whose expected value is 0");
    }

    static void checkImports(String py, List<String> p) {
        Matcher m = IMPORT.matcher(py);
        while (m.find()) {
            List<String> mods = new ArrayList<>();
            if (m.group(1) != null) mods.add(m.group(1));
            else for (String s : m.group(2).split(",")) mods.add(s.trim().split("\\s+")[0]);
            for (String mod : mods) {
                if (mod.isEmpty() || mod.startsWith(".")) continue;
                if (!allowed(mod)) p.add("collector.py imports \"" + clip(mod) + "\": only these standard-library modules are allowed: "
                        + "json, datetime, os, pathlib, sqlite3, csv, re, collections, argparse, ... (no network or subprocess)");
            }
        }
        if (DYNAMIC.matcher(py).find()) p.add("collector.py uses dynamic import/exec/eval");
    }

    private static boolean allowed(String mod) {
        if (ALLOWED_MODULES.contains(mod)) return true;
        if (mod.equals("urllib") || mod.startsWith("urllib.")) return mod.equals("urllib.parse");
        String top = mod.split("\\.")[0];
        // submodules of allowed packages (os.path, xml.etree.ElementTree, collections.abc ...)
        return !top.equals(mod) && ALLOWED_MODULES.contains(top);
    }

    /** Shell-style match of '*' and '?' (case already folded by the caller). */
    static boolean globMatch(String pattern, String s) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return s.matches(re.toString());
    }

    private static String clip(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    // ── contract in plain language (shown at AWAITING_CONTRACT) ─────────────────

    /**
     * "Reads ActivityWatch window + browser activity · no internet · runs daily at 00:10". With an http source:
     * "... api.github.com with your "github" credential (why) · the script itself has no internet · ...".
     */
    public static String contractText(JsonNode manifest) {
        if (manifest == null) return null;
        List<String> reads = new ArrayList<>();
        boolean web = false;
        JsonNode sources = manifest.get("sources");
        if (sources != null && sources.isArray()) {
            for (JsonNode s : sources) {
                String type = s.path("type").asText("");
                if (type.equals("activitywatch")) {
                    List<String> names = new ArrayList<>();
                    s.path("buckets").forEach(bk -> names.add(switch (bk.asText()) {
                        case "window" -> "window";
                        case "web" -> "browser activity";
                        case "afk" -> "away-from-keyboard";
                        default -> bk.asText();
                    }));
                    reads.add("ActivityWatch " + String.join(" + ", names));
                } else if (type.equals("file")) {
                    String why = s.path("why").asText("").strip();
                    reads.add("files " + s.path("glob").asText("") + (why.isEmpty() ? "" : " (" + why + ")"));
                } else if (type.equals("http")) {
                    web = true;
                    String why = s.path("why").asText("").strip();
                    reads.add(hostOf(s.path("url").asText("")) + " with your \"" + s.path("credential").asText("")
                            + "\" credential" + (why.isEmpty() ? "" : " (" + why + ")"));
                }
            }
        }
        String schedule = manifest.path("schedule").asText("");
        String when = schedule.equals("hourly") ? "runs every hour"
                : schedule.startsWith("daily ") ? "runs daily at " + schedule.substring(6) : "runs " + schedule;
        return "Reads " + (reads.isEmpty() ? "nothing" : String.join(", ", reads))
                + (web ? " · your runner makes that call; the script itself has no internet · " : " · no internet · ") + when;
    }

    private static String hostOf(String url) {
        String rest = url.startsWith("https://") ? url.substring(8) : url;
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) end = k;
        }
        return rest.substring(0, end);
    }
}
