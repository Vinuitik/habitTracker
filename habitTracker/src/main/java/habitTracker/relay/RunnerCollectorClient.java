package habitTracker.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runner collector + sensor API (Relay runner/internal/api/collectors.go; contract in docs/designs/kpi-sensors.md
 * "Iteration 1") through RunnerClient, via RunnerJobService.send(): non-2xx → RunnerCallException (status kept:
 * 400 bad bundle, 403 not approved, 409 hash mismatch/busy, 422 collector crashed, 503 sandbox/source unavailable),
 * offline → RunnerUnavailableException. KPI-agnostic: bodies are passed through as JSON.
 */
@Service
public class RunnerCollectorClient {

    /** Same as the runner's idRe: the id goes into a path. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RunnerJobService jobs;
    private final ObjectMapper json;
    /** /test and /run execute the collector in the runner's sandbox (≤ 60s per run, per fixture case for /test). */
    private final long longCallMs;

    public RunnerCollectorClient(RunnerJobService jobs, ObjectMapper json,
                                 @Value("${kpi.runner.long-call-timeout-ms:180000}") long longCallMs) {
        this.jobs = jobs;
        this.json = json;
        this.longCallMs = longCallMs;
    }

    /** What the runner reports about itself: Go runtime.GOOS / GOARCH, package managers on PATH, and its local listen port when it says (null otherwise). */
    public record RunnerInfo(String os, String arch, java.util.List<String> managers, Integer port) {
        public RunnerInfo(String os, String arch, java.util.List<String> managers) {
            this(os, arch, managers, null);
        }
    }

    /**
     * GET /v1/runner/info → os/arch/managers. An older runner (no such fields, or no such route) yields os null,
     * arch null, no managers: callers must tolerate an unknown OS.
     */
    public RunnerInfo info(String userId, String deviceId) {
        JsonNode n;
        try {
            n = jobs.send(userId, deviceId, "GET", "/v1/runner/info", "");
        } catch (RunnerCallException e) {
            if (e.getStatus() == 404) return new RunnerInfo(null, null, java.util.List.of(), null);
            throw e;
        }
        java.util.List<String> managers = new java.util.ArrayList<>();
        if (n != null && n.path("managers").isArray()) n.get("managers").forEach(m -> managers.add(m.asText()));
        String os = n == null ? "" : n.path("os").asText("");
        String arch = n == null ? "" : n.path("arch").asText("");
        int port = n == null ? 0 : n.path("port").asInt(n.path("listenPort").asInt(0));
        return new RunnerInfo(os.isBlank() ? null : os, arch.isBlank() ? null : arch, java.util.List.copyOf(managers),
                port > 0 && port < 65536 ? port : null);
    }

    /** → {activitywatch:{installed, running, buckets, error?}, sandbox:{available, kind, error?}, operation}. */
    public JsonNode sensors(String userId, String deviceId) {
        return jobs.send(userId, deviceId, "GET", "/v1/sensors", "");
    }

    /**
     * KPI credentials held on the runner → [{name, host, auth, updatedAt}] (never values; HUB.md "KPI credentials").
     * 503 = the runner's credential store is not enabled, 404 = an older runner without the endpoint.
     */
    public JsonNode credentials(String userId, String deviceId) {
        return jobs.send(userId, deviceId, "GET", "/v1/credentials", "");
    }

    /** 202; poll sensors() until operation == "". */
    public void installSensor(String userId, String deviceId, String name) {
        jobs.send(userId, deviceId, "POST", "/v1/sensors/install", json.createObjectNode().put("name", name).toString());
    }

    /**
     * Packages Relay installed (or found) on the runner → [{manager, id, installedAt, installedByRelay, usedBy:[collectorId],
     * state: installed|installing|failed|awaiting_confirm, error}]. 404 = a runner older than the package ledger.
     */
    public JsonNode packages(String userId, String deviceId) {
        return jobs.send(userId, deviceId, "GET", "/v1/packages", "");
    }

    /** 202 async. 400 = Relay did not install it (never removed), 409 = a collector still uses it. */
    public void uninstallPackage(String userId, String deviceId, String manager, String id) {
        jobs.send(userId, deviceId, "POST", "/v1/packages/uninstall",
                json.createObjectNode().put("manager", manager).put("id", id).toString());
    }

    /** → [{id, kpiName, version, contractHash, approved, state, lastRunAt, lastError}]. */
    public JsonNode list(String userId, String deviceId) {
        return jobs.send(userId, deviceId, "GET", "/v1/collectors", "");
    }

    /** Same id = new version. → {id, version, contractHash, approved}. */
    public JsonNode install(String userId, String deviceId, String id, String kpiName, JsonNode manifest,
                            Map<String, String> files) {
        ObjectNode body = json.createObjectNode();
        body.put("id", checkId(id));
        body.put("kpiName", kpiName);
        body.set("manifest", manifest);
        ObjectNode f = body.putObject("files");
        files.forEach(f::put);
        return jobs.send(userId, deviceId, "POST", "/v1/collectors", body.toString());
    }

    /** → {passed, sandbox, cases:[{name, passed, expected, got, error?}]}. */
    public JsonNode test(String userId, String deviceId, String id) {
        return jobs.send(userId, deviceId, "POST", "/v1/collectors/" + checkId(id) + "/test", "{}", longCallMs);
    }

    /** → {} (nothing to install) or {state:"installing"|"awaiting_confirm"} when the manifest's requires need installing. */
    public JsonNode approve(String userId, String deviceId, String id, String contractHash) {
        return jobs.send(userId, deviceId, "POST", "/v1/collectors/" + checkId(id) + "/approve",
                json.createObjectNode().put("contractHash", contractHash).toString());
    }

    /** Synchronous dry run → {value, unit, evidence:[{label, value}], sandbox}. */
    public JsonNode run(String userId, String deviceId, String id, OffsetDateTime from, OffsetDateTime to) {
        return jobs.send(userId, deviceId, "POST", "/v1/collectors/" + checkId(id) + "/run",
                json.createObjectNode().put("from", from.toString()).put("to", to.toString()).toString(), longCallMs);
    }

    public void activate(String userId, String deviceId, String id) {
        jobs.send(userId, deviceId, "POST", "/v1/collectors/" + checkId(id) + "/activate", "{}");
    }

    /** since = ISO time (the runner filters on recordedAt), null = all. → [{from, to, value, unit, recordedAt}]. */
    public JsonNode readings(String userId, String deviceId, String id, String since) {
        String q = since == null || since.isBlank() ? "" : "?since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
        return jobs.send(userId, deviceId, "GET", "/v1/collectors/" + checkId(id) + "/readings" + q, "");
    }

    public void delete(String userId, String deviceId, String id) {
        jobs.send(userId, deviceId, "DELETE", "/v1/collectors/" + checkId(id), "");
    }

    public static String checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid collector id");
        return id;
    }
}
