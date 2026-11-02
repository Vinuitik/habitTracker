package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.relay.OnlineRunnerSelector;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerJobService;
import habitTracker.relay.RunnerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Capability LLM calls run on the KPI owner's own Claude Code via their paired Relay runner
 * (job kind "kpi": no project, no tools, text only). The server never spends its own Claude credits.
 * Synchronous like the old HTTP client: start job → poll every pollMs until done/failed or jobTimeoutMs
 * (then the job is cancelled). No online runner → CapabilityGeneratorException.noRunner().
 */
@Component
public class RunnerCapabilityGenerator implements CapabilityGenerator {

    private static final Logger log = LoggerFactory.getLogger(RunnerCapabilityGenerator.class);
    static final String JOB_KIND = "kpi";
    static final int MAX_TURNS = 3; // same as the old CAPABILITY_MAX_TURNS

    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final RunnerJobService jobs;
    private final OnlineRunnerSelector selector;
    private final CapabilityPrompts prompts;
    private final long jobTimeoutMs;
    private final long pollMs;
    private final Sleeper sleeper;

    @Autowired
    public RunnerCapabilityGenerator(RunnerJobService jobs, OnlineRunnerSelector selector, CapabilityPrompts prompts,
                                     @Value("${kpi.capability.job-timeout-ms:300000}") long jobTimeoutMs,
                                     @Value("${kpi.capability.poll-ms:2000}") long pollMs) {
        this(jobs, selector, prompts, jobTimeoutMs, pollMs, Thread::sleep);
    }

    RunnerCapabilityGenerator(RunnerJobService jobs, OnlineRunnerSelector selector, CapabilityPrompts prompts,
                              long jobTimeoutMs, long pollMs, Sleeper sleeper) {
        this.jobs = jobs;
        this.selector = selector;
        this.prompts = prompts;
        this.jobTimeoutMs = jobTimeoutMs;
        this.pollMs = pollMs;
        this.sleeper = sleeper;
    }

    @Override
    public boolean runnerAvailable(String userId) {
        return selector.pick(userId).isPresent();
    }

    @Override
    public Proposal propose(String userId, String kpiName, String description) {
        return prompts.parseProposal(run(userId, prompts.proposePrompt(kpiName, description)));
    }

    @Override
    public String generate(String userId, String kpiName, String description, String proposal,
                           Map<String, List<String>> declaredCapabilities, String previousCode, String previousError) {
        return prompts.parseSourceCode(run(userId, prompts.generatePrompt(kpiName, description, proposal,
                declaredCapabilities, previousCode, previousError)));
    }

    /** → the job's result node (agent's final message). */
    private JsonNode run(String userId, String prompt) {
        String deviceId = selector.pick(userId).orElseThrow(() -> CapabilityGeneratorException.noRunner(null));
        String jobId;
        try {
            jobId = jobs.startJob(userId, deviceId, new RunnerJobService.JobSpec(JOB_KIND, null, prompt, null, MAX_TURNS));
        } catch (RunnerUnavailableException e) {
            throw CapabilityGeneratorException.noRunner(e);
        } catch (RunnerCallException e) {
            throw fromCall(e);
        }
        selector.markUsed(deviceId);

        long deadline = System.currentTimeMillis() + jobTimeoutMs;
        long after = 0;
        while (true) {
            JsonNode snap;
            try {
                snap = jobs.pollJob(userId, deviceId, jobId, after);
            } catch (RunnerUnavailableException e) {
                // Runner dropped mid-job (or a single poll timed out): the job is gone from our point of view.
                throw CapabilityGeneratorException.noRunner(e);
            } catch (RunnerCallException e) {
                throw fromCall(e);
            }
            after = snap.path("nextSeq").asLong(after);
            String state = snap.path("state").asText("");
            if ("done".equals(state)) return snap.get("result");
            if (!"running".equals(state) && !"queued".equals(state) && !state.isEmpty()) {
                String err = snap.path("error").asText("");
                throw new CapabilityGeneratorException("Generation " + state + " on your runner"
                        + (err.isBlank() ? "" : ": " + truncate(err)), false, null);
            }
            if (System.currentTimeMillis() >= deadline) {
                cancelQuietly(userId, deviceId, jobId);
                throw new CapabilityGeneratorException("Generation timed out on your runner after "
                        + jobTimeoutMs / 1000 + "s", false, null);
            }
            try {
                sleeper.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelQuietly(userId, deviceId, jobId);
                throw new CapabilityGeneratorException("Generation interrupted", false, e);
            }
        }
    }

    private void cancelQuietly(String userId, String deviceId, String jobId) {
        try {
            jobs.cancelJob(userId, deviceId, jobId);
        } catch (RuntimeException e) {
            log.debug("cancel of job {} on {} failed: {}", jobId, deviceId, e.toString());
        }
    }

    private static CapabilityGeneratorException fromCall(RunnerCallException e) {
        boolean busy = e.getStatus() == 409 || e.getStatus() == 429;
        return new CapabilityGeneratorException(busy
                ? "Your runner is busy with another job, try again in a minute"
                : "Runner returned " + e.getStatus() + ": " + truncate(e.getBody()), busy, e);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
