package habitTracker.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** Runner job API (POST/GET/DELETE /v1/jobs) through RunnerClient. Non-2xx → RunnerCallException. */
@Service
@RequiredArgsConstructor
public class RunnerJobService {

    /** Job ids go into a path; anything else could address another runner endpoint. */
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final RunnerClient client;
    private final ObjectMapper json;

    /** kind = map | plan | kpi; projectId = the runner's project id (null for none); model/maxTurns optional. */
    public record JobSpec(String kind, String projectId, String prompt, String model, Integer maxTurns) {}

    /** → runner jobId. */
    public String startJob(String userId, String deviceId, JobSpec spec) {
        ObjectNode body = json.createObjectNode();
        body.put("kind", spec.kind());
        if (spec.projectId() != null) body.put("projectId", spec.projectId());
        body.put("prompt", spec.prompt());
        if (spec.model() != null) body.put("model", spec.model());
        if (spec.maxTurns() != null) body.put("maxTurns", spec.maxTurns());
        JsonNode res = send(userId, deviceId, "POST", "/v1/jobs", body.toString());
        String jobId = res.path("jobId").asText("");
        if (jobId.isEmpty()) throw new RunnerCallException(502, "{\"error\":\"runner returned no jobId\"}");
        return jobId;
    }

    /** Raw job snapshot {jobId, kind, state, events, nextSeq, result?, error?, usage?}. */
    public JsonNode pollJob(String userId, String deviceId, String jobId, long after) {
        return send(userId, deviceId, "GET", "/v1/jobs/" + checkId(jobId) + "?after=" + Math.max(0, after), "");
    }

    public void cancelJob(String userId, String deviceId, String jobId) {
        send(userId, deviceId, "DELETE", "/v1/jobs/" + checkId(jobId), "");
    }

    /** Generic JSON call: 2xx → parsed body (empty object when blank), else RunnerCallException. */
    public JsonNode send(String userId, String deviceId, String method, String path, String body) {
        return parse(client.call(userId, deviceId, method, path, body));
    }

    /** Same with an explicit wait for the runner's answer (long synchronous runner calls). */
    public JsonNode send(String userId, String deviceId, String method, String path, String body, long waitMs) {
        return parse(client.call(userId, deviceId, method, path, body, waitMs));
    }

    private JsonNode parse(UplinkRegistry.Response r) {
        if (r.status() < 200 || r.status() >= 300) throw new RunnerCallException(r.status(), r.body());
        if (r.body() == null || r.body().isBlank()) return json.createObjectNode();
        try {
            return json.readTree(r.body());
        } catch (Exception e) {
            throw new RunnerCallException(502, "{\"error\":\"runner returned invalid JSON\"}");
        }
    }

    public static String checkId(String jobId) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) throw new IllegalArgumentException("invalid job id");
        return jobId;
    }
}
