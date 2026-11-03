package habitTracker.Project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.RunnerCallException;
import habitTracker.aiusage.AiUsageService;
import habitTracker.relay.RunnerJobService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

// Project ⇄ Relay runner: link a local folder on the user's runner, build/store a repo map via a runner "map" job.
// Ownership like ProjectService: findByIdAndUserId, so another user's project is a 404.
@Service
public class ProjectRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ProjectRunnerService.class);
    static final int MAX_SUBSYSTEMS = 100;

    private final ProjectRepository projects;
    private final ProjectMapRepository maps;
    private final RunnerJobService runner;
    private final AiUsageService usage;
    private final MongoTemplate mongo;
    private final ObjectMapper json;
    private final Resource promptResource;
    private final int mapMaxTurns;
    private String mapPrompt;

    public ProjectRunnerService(ProjectRepository projects, ProjectMapRepository maps, RunnerJobService runner,
                                AiUsageService usage, MongoTemplate mongo, ObjectMapper json,
                                @Value("classpath:prompts/project-map.md") Resource promptResource,
                                @Value("${relay.map.max-turns:200}") int mapMaxTurns) {
        this.projects = projects;
        this.maps = maps;
        this.runner = runner;
        this.usage = usage;
        this.mongo = mongo;
        this.json = json;
        this.promptResource = promptResource;
        this.mapMaxTurns = mapMaxTurns;
    }

    public static class NotLinkedException extends RuntimeException {
        public NotLinkedException() { super("project is not linked to a runner folder"); }
    }

    // spring.data.mongodb.auto-index-creation is off, so indexes are created here.
    @PostConstruct
    void init() throws IOException {
        mongo.indexOps(ProjectMap.class).ensureIndex(new Index("projectId", Sort.Direction.ASC).unique());
        mapPrompt = promptResource.getContentAsString(UTF_8);
    }

    // ---- link ----

    public Project link(String projectId, String deviceId, String path) {
        Project p = owned(projectId);
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId is required");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        String uid = p.getUserId();
        JsonNode rp = register(uid, deviceId, path.trim());
        String runnerProjectId = rp.path("id").asText("");
        if (runnerProjectId.isEmpty()) throw new RunnerCallException(502, "{\"error\":\"runner returned no project id\"}");
        String name = rp.path("name").asText("");
        p.setRunnerDeviceId(deviceId);
        p.setRunnerProjectId(runnerProjectId);
        p.setFolderName(name.isEmpty() ? lastSegment(path.trim()) : name);
        p.setMapJobId(null);
        return projects.save(p);
    }

    // POST /v1/projects; a 400 usually means "already registered" → reuse the registration with the same path.
    private JsonNode register(String uid, String deviceId, String path) {
        ObjectNode body = json.createObjectNode().put("path", path);
        try {
            return runner.send(uid, deviceId, "POST", "/v1/projects", body.toString());
        } catch (RunnerCallException e) {
            if (e.getStatus() != 400 && e.getStatus() != 409) throw e;
            JsonNode list = runner.send(uid, deviceId, "GET", "/v1/projects", "");
            if (list.isObject() && list.has("projects")) list = list.get("projects");
            for (JsonNode n : list) {
                if (samePath(n.path("path").asText(""), path)) return n;
            }
            throw e;
        }
    }

    // Unlink only: the runner keeps the folder registered and the stored map stays (it describes the repo).
    public Project unlink(String projectId) {
        Project p = owned(projectId);
        p.setRunnerDeviceId(null);
        p.setRunnerProjectId(null);
        p.setFolderName(null);
        p.setMapJobId(null);
        return projects.save(p);
    }

    // ---- map ----

    public String startMap(String projectId) {
        Project p = linked(owned(projectId));
        String jobId = runner.startJob(p.getUserId(), p.getRunnerDeviceId(),
                new RunnerJobService.JobSpec("map", p.getRunnerProjectId(), mapPrompt, null, mapMaxTurns));
        p.setMapJobId(jobId);
        projects.save(p);
        return jobId;
    }

    /** Proxies the runner poll; on state=done stores the map once (adds mapStored / mapError to the response). */
    public JsonNode pollMap(String projectId, String jobId, long after) {
        Project p = linked(owned(projectId));
        if (jobId == null || !jobId.equals(p.getMapJobId())) throw new ProjectService.NotFoundException("job not found");
        JsonNode snap = runner.pollJob(p.getUserId(), p.getRunnerDeviceId(), jobId, after);
        usage.record(p.getUserId(), jobId, snap, AiUsageService.Source.map(p.getId()));
        if (!snap.isObject() || !"done".equals(snap.path("state").asText())) return snap;
        ObjectNode out = ((ObjectNode) snap).deepCopy();
        Optional<ProjectMap> existing = maps.findByProjectId(p.getId());
        if (existing.isPresent() && jobId.equals(existing.get().getJobId())) {
            out.put("mapStored", true);
            return out;
        }
        try {
            ProjectMap m = parse(snap.get("result"));
            m.setId(existing.map(ProjectMap::getId).orElse(null));
            m.setProjectId(p.getId());
            m.setUserId(p.getUserId());
            m.setJobId(jobId);
            m.setBuiltAt(Instant.now());
            maps.save(m);
            out.put("mapStored", true);
        } catch (IllegalArgumentException e) {
            log.warn("[map] project {} job {}: unusable result: {}", p.getId(), jobId, e.getMessage());
            out.put("mapStored", false);
            out.put("mapError", e.getMessage());
        }
        return out;
    }

    public Optional<Map<String, Object>> mapSummary(String projectId) {
        Project p = owned(projectId);
        return maps.findByProjectId(p.getId()).map(m -> {
            List<Map<String, Object>> subs = new ArrayList<>();
            for (ProjectMap.Subsystem s : m.getSubsystems()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", s.getName());
                row.put("paths", s.getPaths());
                subs.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("commitSha", m.getCommitSha());
            out.put("builtAt", m.getBuiltAt());
            out.put("subsystems", subs);
            return out;
        });
    }

    // Result is the agent's final message: a JSON object, or text containing one (fences / prose tolerated).
    ProjectMap parse(JsonNode result) {
        if (result == null || result.isNull()) throw new IllegalArgumentException("job has no result");
        JsonNode root = result;
        if (result.isTextual()) {
            String t = result.asText();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a < 0 || b <= a) throw new IllegalArgumentException("result contains no JSON object");
            try {
                root = json.readTree(t.substring(a, b + 1));
            } catch (IOException e) {
                throw new IllegalArgumentException("result JSON does not parse");
            }
        }
        if (!root.isObject()) throw new IllegalArgumentException("result is not a JSON object");
        JsonNode subs = root.path("subsystems");
        if (!subs.isArray() || subs.isEmpty()) throw new IllegalArgumentException("result has no subsystems");
        if (subs.size() > MAX_SUBSYSTEMS) throw new IllegalArgumentException("too many subsystems");
        List<ProjectMap.Subsystem> list = new ArrayList<>();
        for (JsonNode s : subs) {
            String name = s.path("name").asText("").trim();
            if (name.isEmpty()) throw new IllegalArgumentException("subsystem without a name");
            List<String> paths = new ArrayList<>();
            for (JsonNode path : s.path("paths")) if (path.isTextual()) paths.add(path.asText());
            list.add(new ProjectMap.Subsystem(name, paths, s.path("notes").asText("")));
        }
        String sha = root.path("commitSha").asText("").trim();
        return ProjectMap.builder()
                .commitSha(sha.isEmpty() ? null : sha)
                .index(root.path("index").asText(""))
                .subsystems(list)
                .build();
    }

    // ---- helpers ----

    private Project linked(Project p) {
        if (p.getRunnerDeviceId() == null || p.getRunnerProjectId() == null) throw new NotLinkedException();
        return p;
    }

    private Project owned(String id) {
        String uid = SecurityUtils.getCurrentUserId();
        if (uid == null) throw new IllegalStateException("Not authenticated");
        return projects.findByIdAndUserId(id, uid).orElseThrow(ProjectService.NotFoundException::new);
    }

    static boolean samePath(String a, String b) {
        return trimSlash(a).equals(trimSlash(b));
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        while (t.length() > 1 && (t.endsWith("/") || t.endsWith("\\"))) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String lastSegment(String path) {
        String t = trimSlash(path);
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        return i >= 0 && i < t.length() - 1 ? t.substring(i + 1) : t;
    }
}
