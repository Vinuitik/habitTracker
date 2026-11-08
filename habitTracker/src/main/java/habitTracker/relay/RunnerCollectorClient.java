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

    public void approve(String userId, String deviceId, String id, String contractHash) {
        jobs.send(userId, deviceId, "POST", "/v1/collectors/" + checkId(id) + "/approve",
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
