package habitTracker.aiusage;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.Project.Project;
import habitTracker.Project.ProjectRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Token usage of runner jobs. record() is called by every job poller (ProjectRunnerService.pollMap,
 * PlannerService.poll, RunnerCapabilityGenerator.run) with each snapshot; it stores one row the first time a
 * snapshot is terminal and carries usage. "Exactly once" = unique index on (userId, jobId) + DuplicateKeyException
 * swallowed, so concurrent / repeated polls of the same finished job never double count.
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    static final int RECENT_MAX = 20;
    static final int MAX_DAYS = 365;
    static final String DELETED_PROJECT = "Deleted project";

    /** Who/what a job ran for. projectId for map/plan, kpiName for kpi_*. */
    public record Source(String kind, String projectId, String kpiName) {
        public static Source map(String projectId) { return new Source(AiUsage.MAP, projectId, null); }
        public static Source plan(String projectId) { return new Source(AiUsage.PLAN, projectId, null); }
        public static Source kpi(String kind, String kpiName) { return new Source(kind, null, kpiName); }
    }

    /** Normalized counts; total() is what the UI shows. */
    public record Tokens(long input, long output) {
        public long total() { return input + output; }
    }

    private final AiUsageRepository repository;
    private final ProjectRepository projects;
    private final MongoTemplate mongo;

    // spring.data.mongodb.auto-index-creation is off, so indexes are ensured here.
    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(AiUsage.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("jobId", Sort.Direction.ASC).unique());
        mongo.indexOps(AiUsage.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("at", Sort.Direction.DESC));
    }

    public static boolean isTerminal(String state) {
        return switch (state == null ? "" : state) {
            case "done", "failed", "error", "cancelled", "canceled" -> true;
            default -> false;
        };
    }

    /** Runner usage object → counts; empty when absent or all zero. Accepts camelCase and Anthropic snake_case. */
    public static Optional<Tokens> tokens(JsonNode u) {
        if (u == null || !u.isObject()) return Optional.empty();
        long in = u.has("inputTokens") ? num(u, "inputTokens")
                // raw Anthropic shape: cache reads/writes are separate fields there
                : num(u, "input_tokens") + num(u, "cache_creation_input_tokens") + num(u, "cache_read_input_tokens");
        long out = u.has("outputTokens") ? num(u, "outputTokens") : num(u, "output_tokens");
        if (in + out == 0) in = u.has("totalTokens") ? num(u, "totalTokens") : num(u, "total_tokens");
        return in + out == 0 ? Optional.empty() : Optional.of(new Tokens(in, out));
    }

    private static long num(JsonNode u, String f) {
        JsonNode n = u.get(f);
        return n != null && n.canConvertToLong() ? Math.max(0, n.asLong()) : 0;
    }

    /** Stores the job's usage once, when snap is terminal and has usage. Never throws (usage is best effort). */
    public void record(String userId, String jobId, JsonNode snap, Source source) {
        if (userId == null || jobId == null || snap == null || source == null) return;
        if (!isTerminal(snap.path("state").asText(""))) return;
        Optional<Tokens> t = tokens(snap.get("usage"));
        if (t.isEmpty()) return;
        try {
            if (repository.existsByUserIdAndJobId(userId, jobId)) return;
            repository.insert(AiUsage.builder().userId(userId).jobId(jobId).kind(source.kind())
                    .projectId(source.projectId()).kpiName(source.kpiName())
                    .inputTokens(t.get().input()).outputTokens(t.get().output()).at(Instant.now()).build());
        } catch (DuplicateKeyException e) {
            // a concurrent poll recorded it first
        } catch (RuntimeException e) {
            log.warn("[ai-usage] could not record job {}: {}", jobId, e.toString());
        }
    }

    /** GET /api/ai-usage body. Small per-user volume: aggregated in memory. */
    public Map<String, Object> summary(String userId, int days) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        List<AiUsage> rows = repository.findByUserIdAndAtAfterOrderByAtDesc(userId, Instant.now().minus(Duration.ofDays(d)));

        long in = 0, out = 0;
        Map<String, long[]> byKind = new LinkedHashMap<>();   // kind -> {input, output, runs}
        Map<String, Long> byProject = new LinkedHashMap<>();
        Map<String, Long> byKpi = new LinkedHashMap<>();
        for (AiUsage r : rows) {
            long tok = r.getInputTokens() + r.getOutputTokens();
            in += r.getInputTokens();
            out += r.getOutputTokens();
            long[] k = byKind.computeIfAbsent(String.valueOf(r.getKind()), x -> new long[3]);
            k[0] += r.getInputTokens();
            k[1] += r.getOutputTokens();
            k[2]++;
            if (r.getProjectId() != null) byProject.merge(r.getProjectId(), tok, Long::sum);
            if (r.getKpiName() != null) byKpi.merge(r.getKpiName(), tok, Long::sum);
        }

        Map<String, String> names = new HashMap<>();
        for (Project p : projects.findAllById(byProject.keySet())) {
            if (userId.equals(p.getUserId())) names.put(p.getId(), p.getName());
        }

        List<Map<String, Object>> kinds = new ArrayList<>();
        byKind.forEach((kind, v) -> kinds.add(row("kind", kind, "tokens", v[0] + v[1], "input", v[0], "output", v[1], "runs", v[2])));
        kinds.sort(byTokens());
        List<Map<String, Object>> projectRows = new ArrayList<>();
        byProject.forEach((id, tok) -> projectRows.add(row("projectId", id, "name", names.getOrDefault(id, DELETED_PROJECT), "tokens", tok)));
        projectRows.sort(byTokens());
        List<Map<String, Object>> kpiRows = new ArrayList<>();
        byKpi.forEach((name, tok) -> kpiRows.add(row("kpiName", name, "tokens", tok)));
        kpiRows.sort(byTokens());
        List<Map<String, Object>> recent = new ArrayList<>();
        for (AiUsage r : rows.subList(0, Math.min(RECENT_MAX, rows.size()))) {
            String label = r.getProjectId() != null ? names.getOrDefault(r.getProjectId(), DELETED_PROJECT)
                    : r.getKpiName() != null ? r.getKpiName() : "";
            recent.add(row("at", r.getAt(), "kind", r.getKind(), "label", label,
                    "tokens", r.getInputTokens() + r.getOutputTokens()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", d);
        body.put("totals", row("input", in, "output", out));
        body.put("byKind", kinds);
        body.put("byProject", projectRows);
        body.put("byKpi", kpiRows);
        body.put("recent", recent);
        return body;
    }

    private static Comparator<Map<String, Object>> byTokens() {
        return Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("tokens")).reversed();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
