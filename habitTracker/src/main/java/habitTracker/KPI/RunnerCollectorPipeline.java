package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import habitTracker.relay.OnlineRunnerSelector;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.RunnerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3 RUNNER platform test pipeline (kpi-sensors.md "Iteration 1"), driven by CapabilityAgentService for
 * platform=RUNNER versions:
 *   sensors check (GET /v1/sensors: sandbox.available=false → stop; activitywatch needed but not running → AWAITING_SENSOR)
 *   → generate bundle (runner "kpi" job) → static check (CollectorStaticCheck) → install (POST /v1/collectors, same id
 *     = new version) → fixtures (POST /test; per-case rows in the live steps) — a static/install/fixture failure is fed
 *     back into the next generation, each round counts as an attempt (cap maxAttempts)
 *   → CONTRACT: AWAITING_CONTRACT (user approves in plain language), skipped only when the runner still holds the
 *     approval for exactly this contractHash and the user approved that hash before (repair / "wrong" rounds)
 *   → approveContract(): POST /approve {contractHash} → dryRun(): POST /run for yesterday (hub's local day)
 *   → CapabilityLifecycleService.testGatePassed() → AWAITING_CONFIRM (first build) or live (repair).
 * Activation (POST /activate) happens when the version goes live: RunnerCollectorProvider listens to
 * CapabilityWentLive. Never throws for runner/generator failures: they end as TEST_FAILED / AWAITING_SENSOR with
 * a plain reason; offline runner = CapabilityAgentService.NO_RUNNER_REASON (not an attempt).
 */
@Component
public class RunnerCollectorPipeline {

    private static final Logger log = LoggerFactory.getLogger(RunnerCollectorPipeline.class);
    static final String SANDBOX_UNAVAILABLE = "Runner sandbox unavailable: ";
    static final String ACTIVITYWATCH = "activitywatch";
    static final String HTTP = "http";
    private static final ObjectMapper JSON = new ObjectMapper();

    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final KPIProxyCapabilityRepository repository;
    private final KpiDeploymentRepository deployments;
    private final CapabilityGenerator generator;
    private final RunnerCollectorClient client;
    private final OnlineRunnerSelector selector;
    private final DeviceService devices;
    private final CapabilityLifecycleService lifecycle;
    private final long sensorPollMs;
    private final long sensorInstallTimeoutMs;
    private final Sleeper sleeper;

    @Autowired
    public RunnerCollectorPipeline(KPIProxyCapabilityRepository repository, KpiDeploymentRepository deployments,
                                   CapabilityGenerator generator,
                                   RunnerCollectorClient client, OnlineRunnerSelector selector, DeviceService devices,
                                   CapabilityLifecycleService lifecycle,
                                   @Value("${kpi.runner.sensor-poll-ms:3000}") long sensorPollMs,
                                   @Value("${kpi.runner.sensor-install-timeout-ms:600000}") long sensorInstallTimeoutMs) {
        this(repository, deployments, generator, client, selector, devices, lifecycle, sensorPollMs, sensorInstallTimeoutMs, Thread::sleep);
    }

    RunnerCollectorPipeline(KPIProxyCapabilityRepository repository, KpiDeploymentRepository deployments,
                            CapabilityGenerator generator,
                            RunnerCollectorClient client, OnlineRunnerSelector selector, DeviceService devices,
                            CapabilityLifecycleService lifecycle, long sensorPollMs, long sensorInstallTimeoutMs,
                            Sleeper sleeper) {
        this.repository = repository;
        this.deployments = deployments;
        this.generator = generator;
        this.client = client;
        this.selector = selector;
        this.devices = devices;
        this.lifecycle = lifecycle;
        this.sensorPollMs = sensorPollMs;
        this.sensorInstallTimeoutMs = sensorInstallTimeoutMs;
        this.sleeper = sleeper;
    }

    /** A non-counted stop with a user-facing reason (sandbox missing, runner busy, ...). */
    private static final class Stop extends RuntimeException {
        Stop(String reason) { super(reason); }
    }

    private record Install(String problem, boolean runnerApproved) {}

    // ── generate → static → install → fixtures → (contract) ──────────────────

    /** cap is PENDING_TEST and saved. */
    KPIProxyCapability attempt(KPIProxyCapability cap, KPI kpi, int maxAttempts) {
        String userId = cap.getUserId();
        if (device(cap) == null) {
            Optional<String> d = selector.pick(userId);
            if (d.isEmpty()) return fail(cap, CapabilityAgentService.NO_RUNNER_REASON);
            cap.setTargetDeviceIds(new ArrayList<>(List.of(d.get())));
        }
        deployment(cap); // the proposing device's row: collector id + per-device state
        repository.save(cap);
        try {
            if (sensorsBlock(cap)) return repository.save(cap);
            Install result;
            List<DeviceFacts> facts = deviceFacts(cap);
            while (true) {
                CapabilityProgress.step("Writing the collector on your runner");
                CollectorBundle bundle;
                try {
                    bundle = generator.generateCollector(userId, kpi.getName(), cap.getDescription(), cap.getProposal(),
                            sources(cap), credentials(cap), cap.getCollectorBundle(), cap.getFailureReason(), facts);
                } catch (CapabilityGeneratorException e) {
                    // Not counted: the model never produced a bundle (runner offline, auth expired, busy, bad JSON).
                    return fail(cap, "Code generation failed: " + e.getMessage());
                }
                cap.setCollectorBundle(bundle.toJson());
                cap.setCombine(bundle.combine());
                cap.setCombineWhy(bundle.combineWhy());
                cap.setSourceCode(bundle.collectorPy());
                cap.setAttempts(attempts(cap) + 1);
                cap.setCollectorTests(null);
                result = installAndTest(cap, kpi, bundle, facts);
                if (result.problem() == null) break;
                if (attempts(cap) >= maxAttempts) return fail(cap, result.problem());
                cap.setFailureReason(result.problem());
                repository.save(cap);
                CapabilityProgress.runnerEvent("text", "Did not pass, asking the AI to fix it ("
                        + attempts(cap) + " of " + maxAttempts + " attempts used)");
            }
            cap.setFailureReason(null);
            if (result.runnerApproved() && cap.getContractHash() != null
                    && cap.getContractHash().equals(cap.getApprovedContractHash())) {
                return dryRun(cap, kpi); // same contract the user already approved: no new negotiation
            }
            cap.setStatus(CapabilityStatus.AWAITING_CONTRACT);
            return repository.save(cap);
        } catch (RunnerUnavailableException e) {
            return fail(cap, CapabilityAgentService.NO_RUNNER_REASON);
        } catch (Stop e) {
            return fail(cap, e.getMessage());
        }
    }

    /** true = blocked (cap state set, caller saves). Throws Stop when the runner cannot sandbox at all. */
    private boolean sensorsBlock(KPIProxyCapability cap) {
        boolean needsAw = sources(cap).contains(ACTIVITYWATCH);
        CapabilityProgress.step("Checking the sandbox" + (needsAw ? " and ActivityWatch" : "") + " on " + runnerName(cap));
        JsonNode s = sensors(cap);
        JsonNode sandbox = s.path("sandbox");
        if (!sandbox.path("available").asBoolean(false)) {
            String why = sandbox.path("error").asText("");
            throw new Stop(SANDBOX_UNAVAILABLE + runnerName(cap) + " cannot run collectors in a sandbox"
                    + (why.isBlank() ? "" : " (" + clip(why) + ")") + ", so nothing will run there. Fix the runner's sandbox, then press Regenerate.");
        }
        if (needsAw && !awRunning(s)) {
            cap.setStatus(CapabilityStatus.AWAITING_SENSOR);
            cap.setFailureReason(awMessage(cap, s));
            return true;
        }
        return false;
    }

    private Install installAndTest(KPIProxyCapability cap, KPI kpi, CollectorBundle bundle, List<DeviceFacts> facts) {
        KpiDeployment dep = deployment(cap);
        String userId = cap.getUserId(), device = dep.getDeviceId(), id = dep.getCollectorId();
        CapabilityProgress.step("Checking the collector against the safety rules");
        List<CollectorStaticCheck.Target> targets = facts.stream().map(f -> new CollectorStaticCheck.Target(f.os(), f.managers())).toList();
        List<String> problems = CollectorStaticCheck.check(bundle, sources(cap), CredentialRef.byName(credentials(cap)), targets);
        if (!problems.isEmpty()) {
            problems.stream().limit(5).forEach(p -> CapabilityProgress.runnerEvent("text", "✕ " + clip(p)));
            return new Install("Static check failed: " + String.join("; ", problems.subList(0, Math.min(10, problems.size()))), false);
        }

        CapabilityProgress.step("Installing it on " + runnerName(cap));
        JsonNode installed;
        try {
            if (dep.getOs() == null && !bundle.isLegacy()) dep.setOs(client.info(userId, device).os());
            installed = client.install(userId, device, id, kpi.getName(), bundle.resolve(dep.getOs()), bundle.files());
        } catch (IllegalArgumentException e) { // variants bundle without an entry for this device's OS
            return new Install("The collector does not support " + runnerName(cap) + ": " + e.getMessage(), false);
        } catch (RunnerCallException e) {
            if (e.getStatus() == 400) return new Install("The runner rejected the collector: " + runnerError(e), false);
            throw stopFor(e);
        }
        cap.setContractHash(installed.path("contractHash").asText(null));
        boolean approved = installed.path("approved").asBoolean(false);
        dep.setContractHash(cap.getContractHash());
        dep.setApproved(approved);
        save(dep);
        repository.save(cap);

        CapabilityProgress.step("Testing it with sample data in the runner's sandbox");
        JsonNode test;
        try {
            test = client.test(userId, device, id);
        } catch (RunnerCallException e) {
            throw stopFor(e);
        }
        List<KPIProxyCapability.CollectorTestCase> cases = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (JsonNode c : test.path("cases")) {
            String name = c.path("name").asText("?");
            boolean passed = c.path("passed").asBoolean(false);
            Double expected = c.hasNonNull("expected") ? c.get("expected").asDouble() : null;
            Double got = c.hasNonNull("got") ? c.get("got").asDouble() : null;
            String error = c.hasNonNull("error") ? clip(c.get("error").asText()) : null;
            cases.add(new KPIProxyCapability.CollectorTestCase(name, passed, expected, got, error));
            String line = (passed ? "✓ " : "✕ ") + "Sample \"" + clip(name) + "\": "
                    + (got == null ? "no number" : fmt(got)) + (expected == null ? "" : ", expected " + fmt(expected))
                    + (error == null ? "" : " (" + error + ")");
            CapabilityProgress.runnerEvent("text", line);
            if (!passed) failed.add("case " + name + ": expected " + (expected == null ? "?" : fmt(expected))
                    + ", got " + (got == null ? "nothing" : fmt(got)) + (error == null ? "" : ", error: " + error));
        }
        cap.setCollectorTests(cases);
        boolean passed = test.path("passed").asBoolean(false) && !cases.isEmpty();
        if (!passed) {
            return new Install("Fixture test failed: " + (failed.isEmpty() ? "no fixture case ran" : String.join("; ", failed)), approved);
        }
        return new Install(null, approved);
    }

    // ── contract → dry run ────────────────────────────────────────────────────

    /** "Approve" on the contract: POST /approve with the shown hash, then the dry run on real data. */
    KPIProxyCapability approveContract(KPIProxyCapability cap, KPI kpi) {
        if (cap.getStatus() != CapabilityStatus.AWAITING_CONTRACT) {
            throw new IllegalStateException("Only a collector waiting for contract approval can be approved (status "
                    + cap.getStatus() + ")");
        }
        cap.setStatus(CapabilityStatus.PENDING_TEST);
        repository.save(cap);
        try {
            CapabilityProgress.step("Saving your approval on " + runnerName(cap));
            try {
                KpiDeployment dep = deployment(cap);
                client.approve(cap.getUserId(), dep.getDeviceId(), dep.getCollectorId(), cap.getContractHash());
                dep.setContractHash(cap.getContractHash());
                dep.setApproved(true);
                save(dep);
            } catch (RunnerCallException e) {
                if (e.getStatus() == 409) {
                    return fail(cap, "Test gate failed: the collector on your runner changed after this contract was shown. Press Regenerate.");
                }
                if (e.getStatus() == 404) {
                    return fail(cap, "Test gate failed: the collector is no longer on your runner. Press Regenerate.");
                }
                throw stopFor(e);
            }
            cap.setApprovedContractHash(cap.getContractHash());
            repository.save(cap);
            return dryRun(cap, kpi);
        } catch (RunnerUnavailableException e) {
            cap.setStatus(CapabilityStatus.AWAITING_CONTRACT); // nothing happened on the runner: ask again later
            cap.setFailureReason(CapabilityAgentService.NO_RUNNER_REASON);
            return repository.save(cap);
        } catch (Stop e) {
            return fail(cap, e.getMessage());
        }
    }

    private KPIProxyCapability dryRun(KPIProxyCapability cap, KPI kpi) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = LocalDate.now(zone).minusDays(1);
        OffsetDateTime from = day.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        CapabilityProgress.step("Running it once on your real data for " + day);
        JsonNode r;
        try {
            KpiDeployment dep = deployment(cap);
            r = client.run(cap.getUserId(), dep.getDeviceId(), dep.getCollectorId(), from, to);
        } catch (RunnerCallException e) {
            return switch (e.getStatus()) {
                case 503 -> { // ActivityWatch down / bucket missing / credential missing or rejected: not the collector's
                    // fault, not an attempt. "Check again" (installSensor) retries the dry run.
                    boolean aw = sources(cap).contains(ACTIVITYWATCH), web = sources(cap).contains(HTTP);
                    String fix = aw && web ? "Make sure ActivityWatch is running there and the credential is saved on that runner"
                            : web ? "Add or fix the credential in Relay → " + runnerName(cap) + " → KPI credentials"
                            : "Make sure ActivityWatch is running there";
                    cap.setStatus(CapabilityStatus.AWAITING_SENSOR);
                    cap.setFailureReason(runnerName(cap) + " could not read the collector's data: " + runnerError(e)
                            + ". " + fix + ", then press Check again.");
                    yield repository.save(cap);
                }
                case 422 -> fail(cap, "Test gate failed: the collector crashed on your real data: " + runnerError(e));
                case 403 -> fail(cap, "Test gate failed: your runner says this collector is not approved. Press Regenerate.");
                default -> throw stopFor(e);
            };
        }
        CapabilityOutput out = CapabilityOutput.parse(r.toString());
        if (out == null) {
            return fail(cap, "Test gate failed: the collector ran but did not return a number: " + clip(r.toString()));
        }
        return lifecycle.testGatePassed(cap, kpi, out, day, Boolean.TRUE.equals(cap.getRepair()));
    }

    // ── sensors ──────────────────────────────────────────────────────────────

    /**
     * "Install it" / "Check again" on AWAITING_SENSOR: already running → continue; else POST /v1/sensors/install and
     * poll until the runner's operation is "" (cap kpi.runner.sensor-install-timeout-ms). Then continues where the
     * version stopped: dry run if its contract is approved, else the full attempt.
     */
    KPIProxyCapability installSensor(KPIProxyCapability cap, KPI kpi, int maxAttempts) {
        if (cap.getStatus() != CapabilityStatus.AWAITING_SENSOR) {
            throw new IllegalStateException("Nothing to install for this capability (status " + cap.getStatus() + ")");
        }
        try {
            JsonNode s = sensors(cap);
            boolean needsAw = sources(cap).contains(ACTIVITYWATCH); // http-only: "Check again" just retries the fetch
            if (needsAw && !awRunning(s) && !"installing".equals(s.path("operation").asText(""))) {
                CapabilityProgress.step("Installing ActivityWatch on " + runnerName(cap));
                try {
                    client.installSensor(cap.getUserId(), device(cap), ACTIVITYWATCH);
                } catch (RunnerCallException e) {
                    if (e.getStatus() == 501) {
                        cap.setFailureReason("ActivityWatch can't be installed automatically on " + runnerName(cap)
                                + ". Install it from activitywatch.net, start it, then press Check again.");
                        return repository.save(cap);
                    }
                    if (e.getStatus() != 409) throw stopFor(e); // 409 = an install is already running: just wait
                }
            }
            if (needsAw && !awRunning(s)) {
                CapabilityProgress.step("Waiting for ActivityWatch to start on " + runnerName(cap));
                long deadline = System.currentTimeMillis() + sensorInstallTimeoutMs;
                do {
                    try {
                        sleeper.sleep(sensorPollMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    s = sensors(cap);
                } while (!"".equals(s.path("operation").asText("")) && System.currentTimeMillis() < deadline);
            }
            if (needsAw && !awRunning(s)) {
                cap.setFailureReason(awMessage(cap, s));
                return repository.save(cap);
            }
            cap.setStatus(CapabilityStatus.PENDING_TEST);
            cap.setFailureReason(null);
            repository.save(cap);
            boolean approved = cap.getContractHash() != null && cap.getContractHash().equals(cap.getApprovedContractHash());
            return approved ? dryRun(cap, kpi) : attempt(cap, kpi, maxAttempts);
        } catch (RunnerUnavailableException e) {
            cap.setFailureReason(CapabilityAgentService.NO_RUNNER_REASON);
            return repository.save(cap);
        } catch (Stop e) {
            return fail(cap, e.getMessage());
        }
    }

    /** "Start over" on a first build: remove the collector from the runner (best effort). */
    void forget(KPIProxyCapability cap) {
        if (device(cap) == null) return;
        KpiDeployment dep = deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(cap.getKpiId(), device(cap), cap.getVersion()).orElse(null);
        if (dep == null || dep.getCollectorId() == null) return;
        try {
            client.delete(cap.getUserId(), dep.getDeviceId(), dep.getCollectorId());
        } catch (RuntimeException e) {
            log.info("[RunnerCollector] could not delete collector {} on {}: {}", dep.getCollectorId(), dep.getDeviceId(), e.toString());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The proposing device: first of targetDeviceIds (the build pipeline runs against one device), null when none. */
    static String device(KPIProxyCapability cap) {
        List<String> t = cap.getTargetDeviceIds();
        return t == null || t.isEmpty() ? null : t.get(0);
    }

    /** This version's deployment row on the proposing device; created on first use (collector id "kpi-<kpiId>-v<version>"). */
    KpiDeployment deployment(KPIProxyCapability cap) {
        String device = device(cap);
        if (device == null) return null;
        return deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(cap.getKpiId(), device, cap.getVersion())
                .orElseGet(() -> deployments.save(KpiDeployment.builder()
                        .userId(cap.getUserId()).kpiId(cap.getKpiId()).capabilityId(cap.getId())
                        .capabilityVersion(cap.getVersion()).deviceId(device).collectorId("kpi-" + cap.getKpiId() + "-v" + cap.getVersion())
                        .createdAt(Instant.now()).updatedAt(Instant.now()).build()));
    }

    private void save(KpiDeployment dep) {
        dep.setUpdatedAt(Instant.now());
        deployments.save(dep);
    }

    private JsonNode sensors(KPIProxyCapability cap) {
        try {
            return client.sensors(cap.getUserId(), device(cap));
        } catch (RunnerCallException e) {
            if (e.getStatus() == 503 || e.getStatus() == 404) {
                throw new Stop(SANDBOX_UNAVAILABLE + runnerName(cap) + " does not support KPI collectors yet. Update the runner app, then press Regenerate.");
            }
            throw stopFor(e);
        }
    }

    private static boolean awRunning(JsonNode s) {
        JsonNode aw = s.path("activitywatch");
        return aw.path("installed").asBoolean(false) && aw.path("running").asBoolean(false);
    }

    private String awMessage(KPIProxyCapability cap, JsonNode s) {
        JsonNode aw = s.path("activitywatch");
        String err = aw.path("error").asText("");
        String state = "installing".equals(s.path("operation").asText("")) ? " It is being installed right now."
                : aw.path("installed").asBoolean(false) ? " It is installed but not started." : " It is not installed.";
        return "ActivityWatch isn't running on " + runnerName(cap) + "." + state + (err.isBlank() ? "" : " (" + clip(err) + ")");
    }

    String runnerName(KPIProxyCapability cap) {
        if (device(cap) == null) return "your runner";
        try {
            return devices.owned(cap.getUserId(), device(cap)).map(Device::getName)
                    .filter(n -> n != null && !n.isBlank()).orElse("your runner");
        } catch (RuntimeException e) {
            return "your runner";
        }
    }

    /** The runner the collector goes to + the credentials it holds (names + hosts only). */
    record RunnerCredentials(String deviceId, List<CredentialRef> list) {}

    /**
     * GET /v1/credentials on the runner the next build would use (OnlineRunnerSelector.pick). No runner online →
     * deviceId null + empty list (the propose call then reports "connect a runner"). An older runner (404) or a disabled
     * store (503) → empty list: the plan can still use local sources, and an http plan is told which credential to add.
     */
    RunnerCredentials runnerCredentials(String userId) {
        Optional<String> d = selector.pick(userId);
        if (d.isEmpty()) return new RunnerCredentials(null, List.of());
        List<CredentialRef> out = new ArrayList<>();
        try {
            JsonNode list = client.credentials(userId, d.get());
            if (list != null && list.isArray()) {
                for (JsonNode c : list) {
                    CredentialRef r = new CredentialRef(c.path("name").asText(""), c.path("host").asText(""));
                    if (r.valid()) out.add(r);
                }
            }
        } catch (RunnerCallException e) {
            log.info("[RunnerCollector] credentials list on {} failed: HTTP {}", d.get(), e.getStatus()); // body not logged
        } catch (RunnerUnavailableException e) {
            return new RunnerCredentials(null, List.of());
        }
        return new RunnerCredentials(d.get(), List.copyOf(out));
    }

    /**
     * Facts about every target device for the collector prompt and static check: runner info (os, managers) and the
     * credentials it holds (names + hosts). A device that cannot answer is listed with an unknown OS (legacy manifest).
     */
    List<DeviceFacts> deviceFacts(KPIProxyCapability cap) {
        List<DeviceFacts> out = new ArrayList<>();
        List<String> ids = cap.getTargetDeviceIds() == null ? List.of() : cap.getTargetDeviceIds();
        for (String id : ids) {
            String os = null;
            List<String> managers = List.of();
            try {
                RunnerCollectorClient.RunnerInfo info = client.info(cap.getUserId(), id);
                if (info != null) {
                    os = info.os();
                    managers = info.managers();
                }
            } catch (RunnerCallException | RunnerUnavailableException e) {
                log.info("[RunnerCollector] runner info on {} failed: {}", id, e.getClass().getSimpleName());
            }
            out.add(new DeviceFacts(id, deviceName(cap.getUserId(), id), os, managers, credentialsOn(cap.getUserId(), id)));
        }
        return out;
    }

    String deviceName(String userId, String deviceId) {
        try {
            return devices.owned(userId, deviceId).map(Device::getName).filter(n -> n != null && !n.isBlank()).orElse(deviceId);
        } catch (RuntimeException e) {
            return deviceId;
        }
    }

    /** GET /v1/credentials on one device → names + hosts; empty when it cannot say. */
    List<CredentialRef> credentialsOn(String userId, String deviceId) {
        List<CredentialRef> out = new ArrayList<>();
        try {
            JsonNode list = client.credentials(userId, deviceId);
            if (list != null && list.isArray()) {
                for (JsonNode c : list) {
                    CredentialRef r = new CredentialRef(c.path("name").asText(""), c.path("host").asText(""));
                    if (r.valid()) out.add(r);
                }
            }
        } catch (RunnerCallException | RunnerUnavailableException e) {
            log.info("[RunnerCollector] credentials list on {} failed: {}", deviceId, e.getClass().getSimpleName());
        }
        return out;
    }

    /** declaredCapabilities.credentials ("name@host") → refs; empty when the plan reads no web API. */
    static List<CredentialRef> credentials(KPIProxyCapability cap) {
        if (cap.getDeclaredCapabilities() == null) return List.of();
        List<String> s = cap.getDeclaredCapabilities().get("credentials");
        return s == null ? List.of() : s.stream().map(CredentialRef::parse).toList();
    }

    @SuppressWarnings("unchecked")
    static List<String> sources(KPIProxyCapability cap) {
        if (cap.getDeclaredCapabilities() == null) return List.of();
        List<String> s = cap.getDeclaredCapabilities().get("sources");
        return s == null ? List.of() : s;
    }

    private static Stop stopFor(RunnerCallException e) {
        if (e.getStatus() == 409 || e.getStatus() == 429) return new Stop("Code generation failed: Your runner is busy, try again in a minute");
        if (e.getStatus() == 503) return new Stop(SANDBOX_UNAVAILABLE + runnerError(e));
        log.warn("[RunnerCollector] runner call failed: {} {}", e.getStatus(), clip(String.valueOf(e.getBody())));
        return new Stop("Test gate failed: your runner refused the request (HTTP " + e.getStatus() + "). Check the runner app is up to date.");
    }

    /** The runner's {"error": "..."} text (its own validation message, no secrets), clipped. */
    static String runnerError(RunnerCallException e) {
        try {
            JsonNode n = JSON.readTree(e.getBody());
            String msg = n.path("error").asText("");
            if (!msg.isBlank()) return clip(msg);
        } catch (Exception ignored) {
            // not JSON
        }
        return "HTTP " + e.getStatus();
    }

    private KPIProxyCapability fail(KPIProxyCapability cap, String reason) {
        cap.setStatus(CapabilityStatus.TEST_FAILED);
        cap.setFailureReason(reason);
        log.warn("[RunnerCollector] capability {} v{} failed: {}", cap.getId(), cap.getVersion(), reason);
        return repository.save(cap);
    }

    private static int attempts(KPIProxyCapability c) {
        return c.getAttempts() == null ? 0 : c.getAttempts();
    }

    private static String fmt(double v) {
        return CapabilityOutput.formatValue(v, null);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
