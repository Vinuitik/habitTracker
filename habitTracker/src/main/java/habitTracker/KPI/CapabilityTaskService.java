package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async wrapper for the UI around the blocking CapabilityAgentService calls (propose / approve / regenerate /
 * repair can take minutes while the runner writes code and the runner tests it).
 * start → jobId; the call runs on a small worker pool with a CapabilityProgress sink bound, so stage changes
 * and runner events land in the task's event list; poll(after) → {state, events, nextSeq, result?, error?,
 * code?, usage?}. result is the CapabilityDTO (already parsed, never raw model text).
 *
 * In-memory and single-node: tasks are lost on restart (the capability version itself is persisted by the
 * agent service, so a reload shows its stored status). One running task per (user, KPI). Finished tasks are
 * evicted after KEEP_FINISHED_MS.
 */
@Service
public class CapabilityTaskService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityTaskService.class);
    static final long KEEP_FINISHED_MS = 15 * 60 * 1000L;
    static final int MAX_EVENTS = 200;
    static final int WORKERS = 4;

    enum Action { PROPOSE, APPROVE, REGENERATE, REPAIR, WRONG, CONTRACT, SENSOR }

    record Event(long seq, String kind, String text) {}

    static final class Task {
        final String id;
        final String userId;
        final String kpiName;
        private final List<Event> events = new ArrayList<>();
        private long seq;
        private volatile String state = "running"; // running | done | failed
        private volatile CapabilityDTO result;
        private volatile String error;
        private volatile String code;
        private long inputTokens;
        private long outputTokens;
        private boolean hasUsage;
        private volatile long finishedAt;

        Task(String id, String userId, String kpiName) {
            this.id = id;
            this.userId = userId;
            this.kpiName = kpiName;
        }

        synchronized void add(String kind, String text) {
            if (events.size() >= MAX_EVENTS) return;
            events.add(new Event(++seq, kind, text));
        }

        synchronized void addUsage(JsonNode u) {
            long in = num(u, "input_tokens", "inputTokens") + num(u, "cache_creation_input_tokens", "cacheCreationInputTokens");
            long out = num(u, "output_tokens", "outputTokens");
            if (in + out == 0) in = num(u, "total_tokens", "totalTokens");
            if (in + out == 0) return;
            inputTokens += in;
            outputTokens += out;
            hasUsage = true;
        }

        synchronized Map<String, Object> snapshot(long after) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", id);
            m.put("state", state);
            List<Event> fresh = events.stream().filter(e -> e.seq() > after).toList();
            m.put("events", fresh);
            m.put("nextSeq", Math.max(after, seq));
            if (result != null) m.put("result", result);
            if (error != null) {
                m.put("error", error);
                m.put("code", code);
            }
            if (hasUsage) {
                m.put("usage", Map.of("inputTokens", inputTokens, "outputTokens", outputTokens,
                        "totalTokens", inputTokens + outputTokens));
            }
            return m;
        }

        boolean running() {
            return "running".equals(state);
        }

        private static long num(JsonNode u, String a, String b) {
            JsonNode n = u.has(a) ? u.get(a) : u.get(b);
            return n != null && n.canConvertToLong() ? Math.max(0, n.asLong()) : 0;
        }
    }

    private final CapabilityAgentService agent;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService pool;

    public CapabilityTaskService(CapabilityAgentService agent) {
        this.agent = agent;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(WORKERS, r -> {
            Thread t = new Thread(r, "capability-task-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** userId must be captured on the request thread. → jobId. */
    public String start(String userId, String kpiName, String action, String capabilityId, String description) {
        Action a = parse(action);
        if ((a == Action.APPROVE || a == Action.REGENERATE || a == Action.WRONG || a == Action.CONTRACT || a == Action.SENSOR) && (capabilityId == null || capabilityId.isBlank())) {
            throw new IllegalArgumentException("Missing capability id");
        }
        evictFinished();
        Task task;
        synchronized (this) {
            if (activeTask(userId, kpiName).isPresent()) {
                throw new IllegalStateException("Already working on this KPI. Wait for it to finish");
            }
            task = new Task(UUID.randomUUID().toString(), userId, kpiName);
            tasks.put(task.id, task);
        }
        pool.submit(() -> run(task, a, capabilityId, description));
        return task.id;
    }

    /** Scoped: a task is only visible to its own user and KPI. */
    public Optional<Map<String, Object>> poll(String userId, String kpiName, String jobId, long after) {
        Task t = tasks.get(jobId);
        if (t == null || !t.userId.equals(userId) || !t.kpiName.equals(kpiName)) return Optional.empty();
        return Optional.of(t.snapshot(Math.max(0, after)));
    }

    /** The running task of this KPI, so a reopened dialog can resume watching it. */
    public Optional<String> active(String userId, String kpiName) {
        return activeTask(userId, kpiName).map(t -> t.id);
    }

    private Optional<Task> activeTask(String userId, String kpiName) {
        return tasks.values().stream()
                .filter(t -> t.running() && t.userId.equals(userId) && t.kpiName.equals(kpiName)).findFirst();
    }

    private void run(Task task, Action a, String capabilityId, String description) {
        CapabilityProgress.Sink sink = new CapabilityProgress.Sink() {
            public void step(String text) { task.add("step", text); }
            public void runnerEvent(String kind, String text) { task.add(kind, text); }
            public void usage(JsonNode usage) { task.addUsage(usage); }
        };
        try {
            CapabilityDTO dto = CapabilityProgress.with(sink, () -> switch (a) {
                case PROPOSE -> agent.proposeAs(task.userId, task.kpiName, description);
                case APPROVE -> agent.approveAs(task.userId, task.kpiName, capabilityId);
                case REGENERATE -> agent.regenerateAs(task.userId, task.kpiName, capabilityId);
                case REPAIR -> agent.repairAs(task.userId, task.kpiName);
                case WRONG -> agent.wrongAs(task.userId, task.kpiName, capabilityId, description); // description = the user's reason
                case CONTRACT -> agent.approveContractAs(task.userId, task.kpiName, capabilityId); // S3 RUNNER contract approval
                case SENSOR -> agent.installSensorAs(task.userId, task.kpiName, capabilityId);      // S3 install/check ActivityWatch
            });
            if (dto.status() == CapabilityStatus.TEST_FAILED) task.add("error", "Did not pass");
            task.result = dto;
            task.state = "done";
        } catch (CapabilityGeneratorException e) {
            fail(task, e.getMessage(), e.isNoRunner() ? "runner_offline" : e.isBusy() ? "runner_busy" : "generator");
        } catch (CapabilityRejectedException e) {
            fail(task, e.getMessage(), "rejected");
        } catch (IllegalArgumentException e) {
            fail(task, e.getMessage(), "invalid");
        } catch (IllegalStateException e) {
            fail(task, e.getMessage(), "conflict");
        } catch (RuntimeException e) {
            log.error("[CapabilityTask] {} for KPI '{}' failed", a, task.kpiName, e);
            fail(task, "Something went wrong on the server. Try again in a minute", "internal");
        } finally {
            task.finishedAt = System.currentTimeMillis();
        }
    }

    private static void fail(Task task, String message, String code) {
        task.add("error", message);
        task.error = message;
        task.code = code;
        task.state = "failed";
    }

    private void evictFinished() {
        long cutoff = System.currentTimeMillis() - KEEP_FINISHED_MS;
        tasks.values().removeIf(t -> !t.running() && t.finishedAt > 0 && t.finishedAt < cutoff);
    }

    private static Action parse(String action) {
        try {
            return Action.valueOf(String.valueOf(action).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action (expected propose, approve, regenerate, repair, wrong, contract or sensor)");
        }
    }
}
