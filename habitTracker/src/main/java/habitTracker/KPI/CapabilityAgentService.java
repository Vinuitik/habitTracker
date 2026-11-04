package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M8 agent loop, stages 1-4 for SERVER capabilities:
 *   propose (LLM writes a spec; server rejects what the sandbox cannot grant)
 *   -> approve (user's approval = permission grant) -> generate sourceCode (LLM, scoped to the spec)
 *   -> test gate (dry run in the M7 sandbox for yesterday; exit 0 + parseable number) -> ACTIVE + KPI wired.
 * A passing first build then stops at AWAITING_CONFIRM -> SHADOW -> ACTIVE (CapabilityLifecycleService); repairs go live directly.
 * Failure keeps the version non-active with failureReason stored; regenerate feeds it back (cap MAX_ATTEMPTS).
 * Repair (M12) = a new version seeded with the old code + failure, same test gate.
 *
 * Every public user-facing method scopes by SecurityUtils.getCurrentUserId(); the *As(userId, ...) variants take
 * the id captured on the request thread (CapabilityTaskService runs them on a worker thread with no security
 * context). Capabilities are only ever loaded via (id, userId, kpiId), never by id alone.
 * Stage changes are reported to CapabilityProgress (no-op unless a CapabilityTaskService task is bound).
 */
@Service
public class CapabilityAgentService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAgentService.class);

    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_CONFIRM_ROUNDS = CapabilityLifecycleService.MAX_CONFIRM_ROUNDS;
    private static final int MAX_REASON_CHARS = 500;
    private static final int MAX_DESCRIPTION_CHARS = 2000;
    private final KPIRepository kpiRepository;
    private final KPIProxyCapabilityRepository repository;
    private final CapabilityGenerator generator;
    private final SandboxClient sandboxClient;
    private final CapabilitySecretService secretService;
    private final CapabilityLifecycleService lifecycle;
    private final RunnerCollectorPipeline runnerPipeline;
    private final int timeoutSeconds;

    public CapabilityAgentService(KPIRepository kpiRepository, KPIProxyCapabilityRepository repository,
                                  CapabilityGenerator generator, SandboxClient sandboxClient,
                                  CapabilitySecretService secretService, CapabilityLifecycleService lifecycle,
                                  RunnerCollectorPipeline runnerPipeline,
                                  @Value("${sandbox.script-timeout-seconds:30}") int timeoutSeconds) {
        this.kpiRepository = kpiRepository;
        this.repository = repository;
        this.generator = generator;
        this.sandboxClient = sandboxClient;
        this.secretService = secretService;
        this.lifecycle = lifecycle;
        this.runnerPipeline = runnerPipeline;
        this.timeoutSeconds = timeoutSeconds;
    }

    // ── user-facing (current user) ───────────────────────────────────────────

    public Optional<CapabilityDTO> latest(String kpiName) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        return repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), userId).map(CapabilityDTO::from);
    }

    /** Stages 1-2. Throws CapabilityRejectedException if the sandbox cannot grant what the agent asks for. */
    public CapabilityDTO propose(String kpiName, String description) {
        return proposeAs(currentUser(), kpiName, description);
    }

    CapabilityDTO proposeAs(String userId, String kpiName, String description) {
        KPI kpi = requireKpi(userId, kpiName);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Describe what should be captured");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("Description too long (max " + MAX_DESCRIPTION_CHARS + " chars)");
        }
        Optional<KPIProxyCapability> latest = repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), userId);
        if (latest.isPresent() && (latest.get().getStatus() == CapabilityStatus.ACTIVE
                || latest.get().getStatus() == CapabilityStatus.SHADOW)) {
            throw new IllegalStateException("This KPI already has an active capability");
        }

        CapabilityProgress.step("Drafting a plan on your runner");
        CapabilityGenerator.Proposal p = generator.propose(userId, kpi.getName(), description.trim());
        Map<String, List<String>> granted = checkGrantable(p.platform(), p.declaredCapabilities(), p.sources());
        CapabilityPlatform platform = "RUNNER".equalsIgnoreCase(p.platform()) ? CapabilityPlatform.RUNNER : CapabilityPlatform.SERVER;

        // A newer proposal supersedes any unfinished older one.
        for (KPIProxyCapability old : repository.findByKpiIdAndUserId(kpi.getId(), userId)) {
            if (old.getStatus() == CapabilityStatus.PROPOSED || old.getStatus() == CapabilityStatus.TEST_FAILED
                    || old.getStatus() == CapabilityStatus.PENDING_TEST || old.getStatus() == CapabilityStatus.AWAITING_CONFIRM
                    || old.getStatus() == CapabilityStatus.AWAITING_SENSOR || old.getStatus() == CapabilityStatus.AWAITING_CONTRACT) {
                old.setStatus(CapabilityStatus.REJECTED);
                repository.save(old);
            }
        }
        KPIProxyCapability cap = KPIProxyCapability.builder()
                .userId(userId).kpiId(kpi.getId())
                .platform(platform).language("python")
                .description(description.trim()).proposal(p.proposal())
                .measures(p.measures()).dataSource(p.dataSource())
                .declaredCapabilities(granted)
                .status(CapabilityStatus.PROPOSED)
                .version(latest.map(c -> c.getVersion() == null ? 1 : c.getVersion() + 1).orElse(1))
                .createdAt(LocalDateTime.now())
                .build();
        return CapabilityDTO.from(repository.save(cap));
    }

    /** Stages 3-4. Approval is the permission grant for exactly the stored declaredCapabilities. */
    public CapabilityDTO approve(String kpiName, String capabilityId) {
        return approveAs(currentUser(), kpiName, capabilityId);
    }

    CapabilityDTO approveAs(String userId, String kpiName, String capabilityId) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed capability can be approved (status " + cap.getStatus() + ")");
        }
        checkGrantable(cap); // defence in depth: re-validate the stored spec at the grant moment
        return CapabilityDTO.from(attempt(cap, kpi));
    }

    public CapabilityDTO reject(String kpiName, String capabilityId) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.PROPOSED && cap.getStatus() != CapabilityStatus.TEST_FAILED
                && cap.getStatus() != CapabilityStatus.AWAITING_CONFIRM && cap.getStatus() != CapabilityStatus.AWAITING_SENSOR
                && cap.getStatus() != CapabilityStatus.AWAITING_CONTRACT) {
            throw new IllegalStateException("Cannot reject a capability in status " + cap.getStatus());
        }
        cap.setStatus(CapabilityStatus.REJECTED);
        KPIProxyCapability saved = repository.save(cap);
        // RUNNER first build: take the collector off the runner. Not for a repair: the live version shares its id.
        if (cap.getPlatform() == CapabilityPlatform.RUNNER && !Boolean.TRUE.equals(cap.getRepair())) {
            runnerPipeline.forget(cap);
        }
        return CapabilityDTO.from(saved);
    }

    /** Retry after a failed test gate, feeding the stored failure back into the prompt. */
    public CapabilityDTO regenerate(String kpiName, String capabilityId) {
        return regenerateAs(currentUser(), kpiName, capabilityId);
    }

    CapabilityDTO regenerateAs(String userId, String kpiName, String capabilityId) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.TEST_FAILED) {
            throw new IllegalStateException("Only a failed capability can be regenerated (status " + cap.getStatus() + ")");
        }
        return CapabilityDTO.from(retry(cap, kpi));
    }

    /** Confirm gate, "Looks right": AWAITING_CONFIRM -> SHADOW. Instant, no LLM. */
    public CapabilityDTO confirm(String kpiName, String capabilityId) {
        return confirmAs(currentUser(), kpiName, capabilityId);
    }

    CapabilityDTO confirmAs(String userId, String kpiName, String capabilityId) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        return CapabilityDTO.from(lifecycle.confirmRight(cap, kpi));
    }

    /** S3 RUNNER contract "Approve": POST /approve {contractHash} on the runner, then the dry run on real data. */
    public CapabilityDTO approveContract(String kpiName, String capabilityId) {
        return approveContractAs(currentUser(), kpiName, capabilityId);
    }

    CapabilityDTO approveContractAs(String userId, String kpiName, String capabilityId) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getPlatform() != CapabilityPlatform.RUNNER) throw new IllegalStateException("Only runner collectors have a contract");
        return CapabilityDTO.from(runnerPipeline.approveContract(cap, kpi));
    }

    /** S3 RUNNER "Install it" / "Check again" while AWAITING_SENSOR (ActivityWatch missing on the runner). */
    public CapabilityDTO installSensor(String kpiName, String capabilityId) {
        return installSensorAs(currentUser(), kpiName, capabilityId);
    }

    CapabilityDTO installSensorAs(String userId, String kpiName, String capabilityId) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getPlatform() != CapabilityPlatform.RUNNER) throw new IllegalStateException("Only runner collectors use sensors");
        return CapabilityDTO.from(runnerPipeline.installSensor(cap, kpi, MAX_ATTEMPTS));
    }

    /**
     * Confirm gate, "Wrong, because...": the user's reason is appended to the generation prompt and the version goes
     * back through generate + test gate (-> AWAITING_CONFIRM again). The MAX_CONFIRM_ROUNDS-th "Wrong" ends in
     * TEST_FAILED with a clear sentence. Not limited by MAX_ATTEMPTS (that cap is for test-gate failures).
     */
    public CapabilityDTO wrong(String kpiName, String capabilityId, String reason) {
        return wrongAs(currentUser(), kpiName, capabilityId, reason);
    }

    CapabilityDTO wrongAs(String userId, String kpiName, String capabilityId, String reason) {
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException("Only a capability waiting for confirmation can be marked wrong (status "
                    + cap.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say what is wrong with the number");
        }
        if (reason.length() > MAX_REASON_CHARS) {
            throw new IllegalArgumentException("Reason too long (max " + MAX_REASON_CHARS + " chars)");
        }
        String feedback = CapabilityLifecycleService.wrongFeedback(cap, reason.trim());
        if (!lifecycle.recordWrong(cap)) {
            return CapabilityDTO.from(fail(cap, CapabilityLifecycleService.giveUpSentence()));
        }
        cap.setFailureReason(feedback);
        checkGrantable(cap);
        return CapabilityDTO.from(attempt(cap, kpi));
    }

    /** Manual "fix it": same pipeline as the automatic M12 repair trigger, single attempt. */
    public CapabilityDTO repair(String kpiName) {
        return repairAs(currentUser(), kpiName);
    }

    CapabilityDTO repairAs(String userId, String kpiName) {
        KPI kpi = requireKpi(userId, kpiName);
        return CapabilityDTO.from(repairFor(userId, kpi, false));
    }

    // ── M12 repair (also called from the nightly job thread: no SecurityUtils there) ──

    /**
     * Re-runs implementation + test gate seeded with the failure, as a NEW version. On pass the
     * KPI's proxy breaker is cleared. autoRetry loops up to MAX_ATTEMPTS (background caller only).
     */
    public KPIProxyCapability repairFor(String userId, KPI kpi, boolean autoRetry) {
        return CapabilityProgress.repairing(() -> doRepair(userId, kpi, autoRetry));
    }

    private KPIProxyCapability doRepair(String userId, KPI kpi, boolean autoRetry) {
        if (kpi.getProxyType() != ProxyType.SANDBOX_CAPABILITY && kpi.getProxyType() != ProxyType.RUNNER_COLLECTOR) {
            throw new IllegalStateException("KPI does not use a sandbox capability or runner collector");
        }
        KPIProxyCapability latest = repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("No capability to repair"));
        KPIProxyCapability target;
        switch (latest.getStatus()) {
            case TEST_FAILED -> target = latest; // a previous repair/generation failed: keep working on it
            case ACTIVE, NEEDS_REPAIR, SHADOW -> {
                if (kpi.getProxyStatus() != ProxyStatus.NEEDS_REPAIR) {
                    throw new IllegalStateException("This KPI's proxy is not flagged for repair");
                }
                latest.setStatus(CapabilityStatus.NEEDS_REPAIR);
                repository.save(latest);
                target = repository.save(KPIProxyCapability.builder()
                        .userId(userId).kpiId(kpi.getId()).platform(latest.getPlatform()).language(latest.getLanguage())
                        .description(latest.getDescription()).proposal(latest.getProposal())
                        .measures(latest.getMeasures()).dataSource(latest.getDataSource())
                        .declaredCapabilities(latest.getDeclaredCapabilities())
                        .sourceCode(latest.getSourceCode())
                        // RUNNER: same collector id on the same runner; the approved contract hash carries over
                        // (unchanged hash → no new approval; changed → AWAITING_CONTRACT, never auto-approved)
                        .runnerDeviceId(latest.getRunnerDeviceId()).collectorId(latest.getCollectorId())
                        .collectorBundle(latest.getCollectorBundle()).contractHash(latest.getContractHash())
                        .approvedContractHash(latest.getApprovedContractHash())
                        .repair(true).shadowUntil(latest.getShadowUntil())
                        .failureReason(repairReason(latest))
                        .status(CapabilityStatus.PENDING_TEST)
                        .version(latest.getVersion() == null ? 2 : latest.getVersion() + 1)
                        .createdAt(LocalDateTime.now()).build());
            }
            default -> throw new IllegalStateException("Cannot repair a capability in status " + latest.getStatus());
        }
        KPIProxyCapability result = retry(target, kpi);
        for (int i = 1; autoRetry && i < MAX_ATTEMPTS && result.getStatus() == CapabilityStatus.TEST_FAILED
                && attemptsOf(result) < MAX_ATTEMPTS && !waitingForRunner(result); i++) {
            result = retry(result, kpi);
        }
        return result;
    }

    /** True when this version failed only because no runner of its owner was online (not counted as an attempt). */
    public static boolean waitingForRunner(KPIProxyCapability c) {
        return c != null && c.getStatus() == CapabilityStatus.TEST_FAILED && NO_RUNNER_REASON.equals(c.getFailureReason());
    }

    // ── internals ────────────────────────────────────────────────────────────

    static final String NO_RUNNER_REASON = "Code generation failed: " + CapabilityGeneratorException.NO_RUNNER_MESSAGE;

    private static String repairReason(KPIProxyCapability c) {
        String last = c.getLastResult();
        if (last == null) return "The capability stopped producing valid values";
        if (last.startsWith("OK") || last.startsWith("Test gate OK")) {
            return "It ran but produced a value far outside the KPI's historical range (" + last + ")";
        }
        return "Recent production runs failed: " + last;
    }

    private KPIProxyCapability retry(KPIProxyCapability cap, KPI kpi) {
        boolean repairSeed = cap.getStatus() == CapabilityStatus.PENDING_TEST;
        if (cap.getStatus() != CapabilityStatus.TEST_FAILED && !repairSeed) {
            throw new IllegalStateException("Only a failed capability can be regenerated (status " + cap.getStatus() + ")");
        }
        if (attemptsOf(cap) >= MAX_ATTEMPTS) {
            throw new IllegalStateException("Attempt limit reached (" + MAX_ATTEMPTS + "); propose again with a different description");
        }
        checkGrantable(cap);
        return attempt(cap, kpi);
    }

    /** One generate + test-gate pass. Never throws for generator/sandbox failures: they become TEST_FAILED. */
    private KPIProxyCapability attempt(KPIProxyCapability cap, KPI kpi) {
        cap.setStatus(CapabilityStatus.PENDING_TEST);
        repository.save(cap);
        if (cap.getPlatform() == CapabilityPlatform.RUNNER) {
            return runnerPipeline.attempt(cap, kpi, MAX_ATTEMPTS);
        }

        // A declared secret the user has not set can never pass the gate: say so now instead of burning an LLM call.
        List<String> unset = secretService.missing(cap.getUserId(),
                CapabilityGrants.secrets(CapabilityGrants.normalize(cap.getDeclaredCapabilities())));
        if (!unset.isEmpty()) {
            return fail(cap, "Test gate failed: " + new MissingSecretException(unset).getMessage()
                    + ". Set it in the Automate dialog, then retry.");
        }

        String code;
        CapabilityProgress.step("Writing the script on your runner");
        try {
            code = generator.generate(cap.getUserId(), kpi.getName(), cap.getDescription(), cap.getProposal(),
                    cap.getDeclaredCapabilities(), cap.getSourceCode(), cap.getFailureReason());
        } catch (CapabilityGeneratorException e) {
            // Not counted as an attempt: the model never produced code (CLI down, auth expired, busy, bad JSON).
            return fail(cap, "Code generation failed: " + e.getMessage());
        }
        cap.setSourceCode(code);
        cap.setAttempts(attemptsOf(cap) + 1);

        LocalDate sampleDate = LocalDate.now().minusDays(1);
        SandboxClient.Request request;
        try {
            request = secretService.requestFor(cap, cap.getUserId(), "TARGET_DATE = \"" + sampleDate + "\"\n" + code,
                    timeoutSeconds);
        } catch (MissingSecretException e) { // deleted between the pre-check and now
            return fail(cap, "Test gate failed: " + e.getMessage());
        }
        CapabilityProgress.step("Testing the script in the sandbox for " + sampleDate);
        SandboxClient.Result result = sandboxClient.run(request);
        CapabilityOutput out = result.ok() ? CapabilityOutput.parse(result.stdout()) : null;
        if (out == null) {
            String detail = result.ok()
                    ? "script exited 0 but the last stdout line was not a number or a {\"value\": ...} JSON object: " + truncate(result.stdout())
                    : result.outcome() + (result.exitCode() != null ? " exit=" + result.exitCode() : "") + ": "
                            + truncate(result.stderr() == null || result.stderr().isBlank() ? result.stdout() : result.stderr());
            return fail(cap, "Test gate failed: " + detail);
        }
        return lifecycle.testGatePassed(cap, kpi, out, sampleDate, Boolean.TRUE.equals(cap.getRepair()));
    }

    private KPIProxyCapability fail(KPIProxyCapability cap, String reason) {
        cap.setStatus(CapabilityStatus.TEST_FAILED);
        cap.setFailureReason(reason);
        log.warn("[CapabilityAgent] capability {} v{} failed: {}", cap.getId(), cap.getVersion(), reason);
        return repository.save(cap);
    }

    private static void checkGrantable(KPIProxyCapability c) {
        checkGrantable(c.getPlatform() == null ? null : c.getPlatform().name(), c.getDeclaredCapabilities(),
                RunnerCollectorPipeline.sources(c));
    }

    // The grant boundary: SERVER only; declared network hosts / secret names must be valid (see CapabilityGrants).
    // Approving the proposal grants exactly the returned (normalized) declaredCapabilities and nothing else.
    // RUNNER (S3): no hosts, no secrets (a collector never has network), sources ⊆ {activitywatch, file}, ≥ 1;
    // stored as {network: [], secrets: [], sources: [...]}. The runner sandbox + the contract approval enforce it.
    private static Map<String, List<String>> checkGrantable(String platform, Map<String, List<String>> declared,
                                                            List<String> sources) {
        if (platform != null && "RUNNER".equalsIgnoreCase(platform)) {
            Map<String, List<String>> norm = CapabilityGrants.normalize(declared);
            if (!norm.get("network").isEmpty() || !norm.get("secrets").isEmpty()) {
                throw new CapabilityRejectedException("A collector on your runner never gets internet access or secrets; "
                        + "this KPI needs a server capability instead.");
            }
            List<String> src = sources == null ? List.of() : sources.stream().distinct().toList();
            if (src.isEmpty() || !List.of(RunnerCollectorPipeline.ACTIVITYWATCH, "file").containsAll(src)) {
                throw new CapabilityRejectedException("A runner collector must read ActivityWatch and/or local files "
                        + "(the agent proposed sources " + src + ").");
            }
            Map<String, List<String>> out = new java.util.LinkedHashMap<>(norm);
            out.put("sources", src);
            return out;
        }
        if (platform != null && !"SERVER".equalsIgnoreCase(platform)) {
            throw new CapabilityRejectedException("Not supported yet: only SERVER and RUNNER capabilities can be generated "
                    + "(the agent proposed platform " + platform + ").");
        }
        return CapabilityGrants.normalize(declared);
    }

    private static int attemptsOf(KPIProxyCapability c) {
        return c.getAttempts() == null ? 0 : c.getAttempts();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 500 ? t.substring(0, 500) + "..." : t;
    }

    private static String currentUser() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) throw new IllegalStateException("Not authenticated");
        return userId;
    }

    private KPI requireKpi(String userId, String kpiName) {
        return kpiRepository.findByNameAndUserId(kpiName, userId)
                .orElseThrow(() -> new IllegalArgumentException("KPI with name '" + kpiName + "' does not exist"));
    }

    private KPIProxyCapability requireCapability(String userId, KPI kpi, String capabilityId) {
        return repository.findByIdAndUserIdAndKpiId(capabilityId, userId, kpi.getId())
                .orElseThrow(() -> new IllegalArgumentException("Capability not found"));
    }
}
