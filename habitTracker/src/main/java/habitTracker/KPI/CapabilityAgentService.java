package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M8 agent loop, stages 1-4 for SERVER capabilities:
 *   propose (LLM researches and offers options labelled with cost / method / difficulty; options the
 *   sandbox cannot grant are kept but blocked) -> refine (user replies, LLM revises the options, repeatable)
 *   -> approve one option (user's approval = permission grant) -> generate sourceCode (LLM, scoped to it)
 *   -> test gate (dry run in the M7 sandbox for yesterday; exit 0 + parseable number) -> ACTIVE + KPI wired.
 * Failure keeps the version non-active with failureReason stored; regenerate feeds it back (cap MAX_ATTEMPTS).
 * Repair (M12) = a new version seeded with the old code + failure, same test gate.
 *
 * Every public user-facing method scopes by SecurityUtils.getCurrentUserId(); capabilities are only
 * ever loaded via (id, userId, kpiId), never by id alone.
 */
@Service
public class CapabilityAgentService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAgentService.class);

    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_REFINEMENTS = 8;
    private static final int MAX_OPTIONS = 4;
    private static final int MAX_DESCRIPTION_CHARS = 2000;
    private final KPIRepository kpiRepository;
    private final KPIProxyCapabilityRepository repository;
    private final CapabilityGenerator generator;
    private final SandboxClient sandboxClient;
    private final CapabilitySecretService secretService;
    private final int timeoutSeconds;

    public CapabilityAgentService(KPIRepository kpiRepository, KPIProxyCapabilityRepository repository,
                                  CapabilityGenerator generator, SandboxClient sandboxClient,
                                  CapabilitySecretService secretService,
                                  @Value("${sandbox.script-timeout-seconds:30}") int timeoutSeconds) {
        this.kpiRepository = kpiRepository;
        this.repository = repository;
        this.generator = generator;
        this.sandboxClient = sandboxClient;
        this.secretService = secretService;
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
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Describe what should be captured");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("Description too long (max " + MAX_DESCRIPTION_CHARS + " chars)");
        }
        Optional<KPIProxyCapability> latest = repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), userId);
        if (latest.isPresent() && latest.get().getStatus() == CapabilityStatus.ACTIVE) {
            throw new IllegalStateException("This KPI already has an active capability");
        }

        CapabilityGenerator.Proposal p = generator.propose(kpi.getName(), description.trim(), List.of(), List.of());
        List<CapabilityOption> options = vet(p.options());

        // A newer proposal supersedes any unfinished older one.
        for (KPIProxyCapability old : repository.findByKpiIdAndUserId(kpi.getId(), userId)) {
            if (old.getStatus() == CapabilityStatus.PROPOSED || old.getStatus() == CapabilityStatus.TEST_FAILED
                    || old.getStatus() == CapabilityStatus.PENDING_TEST) {
                old.setStatus(CapabilityStatus.REJECTED);
                repository.save(old);
            }
        }
        KPIProxyCapability cap = KPIProxyCapability.builder()
                .userId(userId).kpiId(kpi.getId())
                .platform(CapabilityPlatform.SERVER).language("python")
                .description(description.trim())
                .options(options)
                .conversation(withReply(new ArrayList<>(), p.reply()))
                .status(CapabilityStatus.PROPOSED)
                .version(latest.map(c -> c.getVersion() == null ? 1 : c.getVersion() + 1).orElse(1))
                .createdAt(LocalDateTime.now())
                .build();
        presetSingle(cap);
        return CapabilityDTO.from(repository.save(cap));
    }

    /**
     * The user answers the proposal ("only free options", "use Garmin instead"); the agent revises the
     * options. Same version, still PROPOSED. Nothing is granted until approve.
     */
    public CapabilityDTO refine(String kpiName, String capabilityId, String message) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed capability can be refined (status " + cap.getStatus() + ")");
        }
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Write what should change");
        if (message.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("Message too long (max " + MAX_DESCRIPTION_CHARS + " chars)");
        }
        if (refinementsLeft(cap) <= 0) {
            throw new IllegalStateException("Refinement limit reached (" + MAX_REFINEMENTS + "); start over with a new description");
        }
        List<CapabilityTurn> convo = new ArrayList<>(cap.getConversation() == null ? List.of() : cap.getConversation());
        convo.add(new CapabilityTurn(CapabilityTurn.USER, message.trim()));
        CapabilityGenerator.Proposal p = generator.propose(kpi.getName(), cap.getDescription(), List.copyOf(convo),
                cap.getOptions() == null ? List.of() : cap.getOptions());
        cap.setOptions(vet(p.options())); // all blocked -> 422, nothing saved (the user's message is not kept)
        cap.setConversation(withReply(convo, p.reply()));
        presetSingle(cap);
        return CapabilityDTO.from(repository.save(cap));
    }

    static int refinementsLeft(KPIProxyCapability c) {
        long used = c.getConversation() == null ? 0
                : c.getConversation().stream().filter(t -> CapabilityTurn.USER.equals(t.role())).count();
        return (int) Math.max(0, MAX_REFINEMENTS - used);
    }

    /** Stages 3-4. Approval is the permission grant for exactly the stored declaredCapabilities. */
    public CapabilityDTO approve(String kpiName, String capabilityId, Integer optionIndex) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed capability can be approved (status " + cap.getStatus() + ")");
        }
        List<CapabilityOption> options = cap.getOptions();
        if (options != null && !options.isEmpty()) { // legacy rows (no options) approve their stored spec
            if (optionIndex == null && options.size() != 1) throw new IllegalArgumentException("Pick one of the options to approve");
            int idx = optionIndex == null ? 0 : optionIndex;
            if (idx < 0 || idx >= options.size()) throw new IllegalArgumentException("No such option");
            CapabilityOption chosen = options.get(idx);
            if (chosen.blockedReason() != null) throw new CapabilityRejectedException(chosen.blockedReason());
            cap.setProposal(chosen.title() == null || chosen.title().isBlank()
                    ? chosen.summary() : chosen.title() + ": " + chosen.summary());
            cap.setDeclaredCapabilities(checkGrantable(chosen.platform(), chosen.declaredCapabilities()));
            cap.setSelectedOption(idx);
        }
        checkGrantable(cap); // defence in depth: re-validate the stored spec at the grant moment
        return CapabilityDTO.from(attempt(cap, kpi));
    }

    public CapabilityDTO reject(String kpiName, String capabilityId) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.PROPOSED && cap.getStatus() != CapabilityStatus.TEST_FAILED) {
            throw new IllegalStateException("Cannot reject a capability in status " + cap.getStatus());
        }
        cap.setStatus(CapabilityStatus.REJECTED);
        return CapabilityDTO.from(repository.save(cap));
    }

    /** Retry after a failed test gate, feeding the stored failure back into the prompt. */
    public CapabilityDTO regenerate(String kpiName, String capabilityId) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        KPIProxyCapability cap = requireCapability(userId, kpi, capabilityId);
        if (cap.getStatus() != CapabilityStatus.TEST_FAILED) {
            throw new IllegalStateException("Only a failed capability can be regenerated (status " + cap.getStatus() + ")");
        }
        return CapabilityDTO.from(retry(cap, kpi));
    }

    /** Manual "fix it": same pipeline as the automatic M12 repair trigger, single attempt. */
    public CapabilityDTO repair(String kpiName) {
        String userId = currentUser();
        KPI kpi = requireKpi(userId, kpiName);
        return CapabilityDTO.from(repairFor(userId, kpi, false));
    }

    // ── M12 repair (also called from the nightly job thread: no SecurityUtils there) ──

    /**
     * Re-runs implementation + test gate seeded with the failure, as a NEW version. On pass the
     * KPI's proxy breaker is cleared. autoRetry loops up to MAX_ATTEMPTS (background caller only).
     */
    public KPIProxyCapability repairFor(String userId, KPI kpi, boolean autoRetry) {
        if (kpi.getProxyType() != ProxyType.SANDBOX_CAPABILITY) {
            throw new IllegalStateException("KPI does not use a sandbox capability");
        }
        KPIProxyCapability latest = repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("No capability to repair"));
        KPIProxyCapability target;
        switch (latest.getStatus()) {
            case TEST_FAILED -> target = latest; // a previous repair/generation failed: keep working on it
            case ACTIVE, NEEDS_REPAIR -> {
                if (kpi.getProxyStatus() != ProxyStatus.NEEDS_REPAIR) {
                    throw new IllegalStateException("This KPI's proxy is not flagged for repair");
                }
                latest.setStatus(CapabilityStatus.NEEDS_REPAIR);
                repository.save(latest);
                target = repository.save(KPIProxyCapability.builder()
                        .userId(userId).kpiId(kpi.getId()).platform(latest.getPlatform()).language(latest.getLanguage())
                        .description(latest.getDescription()).proposal(latest.getProposal())
                        .declaredCapabilities(latest.getDeclaredCapabilities())
                        .sourceCode(latest.getSourceCode())
                        .failureReason(repairReason(latest))
                        .status(CapabilityStatus.PENDING_TEST)
                        .version(latest.getVersion() == null ? 2 : latest.getVersion() + 1)
                        .createdAt(LocalDateTime.now()).build());
            }
            default -> throw new IllegalStateException("Cannot repair a capability in status " + latest.getStatus());
        }
        KPIProxyCapability result = retry(target, kpi);
        for (int i = 1; autoRetry && i < MAX_ATTEMPTS && result.getStatus() == CapabilityStatus.TEST_FAILED
                && attemptsOf(result) < MAX_ATTEMPTS; i++) {
            result = retry(result, kpi);
        }
        return result;
    }

    // ── internals ────────────────────────────────────────────────────────────

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

        // A declared secret the user has not set can never pass the gate: say so now instead of burning an LLM call.
        List<String> unset = secretService.missing(cap.getUserId(),
                CapabilityGrants.secrets(CapabilityGrants.normalize(cap.getDeclaredCapabilities())));
        if (!unset.isEmpty()) {
            return fail(cap, "Test gate failed: " + new MissingSecretException(unset).getMessage()
                    + ". Set it in the Automate dialog, then retry.");
        }

        String code;
        try {
            code = generator.generate(kpi.getName(), cap.getDescription(), cap.getProposal(),
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
        SandboxClient.Result result = sandboxClient.run(request);
        Double value = result.ok() ? SandboxCapabilityProvider.parseLastLine(result.stdout()) : null;
        if (value == null) {
            String detail = result.ok()
                    ? "script exited 0 but the last stdout line was not a number: " + truncate(result.stdout())
                    : result.outcome() + (result.exitCode() != null ? " exit=" + result.exitCode() : "") + ": "
                            + truncate(result.stderr() == null || result.stderr().isBlank() ? result.stdout() : result.stderr());
            return fail(cap, "Test gate failed: " + detail);
        }

        cap.setStatus(CapabilityStatus.ACTIVE);
        cap.setFailureReason(null);
        cap.setLastRunAt(LocalDateTime.now());
        cap.setLastResult("Test gate OK for " + sampleDate + ": " + value);
        cap.setConsecutiveFailures(0);
        repository.save(cap);
        activate(cap, kpi);
        return cap;
    }

    private KPIProxyCapability fail(KPIProxyCapability cap, String reason) {
        cap.setStatus(CapabilityStatus.TEST_FAILED);
        cap.setFailureReason(reason);
        log.warn("[CapabilityAgent] capability {} v{} failed: {}", cap.getId(), cap.getVersion(), reason);
        return repository.save(cap);
    }

    /** Wires the KPI to the capability and retires older live versions. */
    private void activate(KPIProxyCapability cap, KPI kpi) {
        for (KPIProxyCapability other : repository.findByKpiIdAndUserId(cap.getKpiId(), cap.getUserId())) {
            if (!other.getId().equals(cap.getId())
                    && (other.getStatus() == CapabilityStatus.ACTIVE || other.getStatus() == CapabilityStatus.NEEDS_REPAIR)) {
                other.setStatus(CapabilityStatus.DISABLED);
                repository.save(other);
            }
        }
        KPI fresh = kpiRepository.findById(kpi.getId()).orElse(kpi);
        fresh.setProxyType(ProxyType.SANDBOX_CAPABILITY);
        fresh.setProxyStatus(ProxyStatus.ACTIVE);
        fresh.setConsecutiveProxyFailures(0);
        fresh.setUpdatedAt(LocalDateTime.now());
        kpiRepository.save(fresh);
    }

    /** Marks options the sandbox cannot grant (kept, shown, not approvable). All blocked -> 422, nothing stored. */
    private static List<CapabilityOption> vet(List<CapabilityOption> raw) {
        if (raw == null || raw.isEmpty()) throw new CapabilityGeneratorException("The agent returned no options", false, null);
        List<CapabilityOption> out = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (CapabilityOption o : raw.subList(0, Math.min(raw.size(), MAX_OPTIONS))) {
            try {
                out.add(o.vetted(checkGrantable(o.platform(), o.declaredCapabilities()), null));
            } catch (CapabilityRejectedException e) {
                out.add(o.vetted(o.declaredCapabilities(), e.getMessage()));
                reasons.add(e.getMessage());
            }
        }
        if (reasons.size() == out.size()) throw new CapabilityRejectedException(String.join(" ", reasons));
        return out;
    }

    // A lone option needs no choice: expose it as the spec right away (approve without a body works).
    private static void presetSingle(KPIProxyCapability cap) {
        boolean single = cap.getOptions().size() == 1;
        cap.setProposal(single ? cap.getOptions().get(0).summary() : null);
        cap.setDeclaredCapabilities(single ? cap.getOptions().get(0).declaredCapabilities() : null);
    }

    private static List<CapabilityTurn> withReply(List<CapabilityTurn> convo, String reply) {
        if (reply != null && !reply.isBlank()) convo.add(new CapabilityTurn(CapabilityTurn.AGENT, reply.strip()));
        return convo;
    }

    private static void checkGrantable(KPIProxyCapability c) {
        checkGrantable(c.getPlatform() == null ? null : c.getPlatform().name(), c.getDeclaredCapabilities());
    }

    // The grant boundary: SERVER only; declared network hosts / secret names must be valid (see CapabilityGrants).
    // Approving the proposal grants exactly the returned (normalized) declaredCapabilities and nothing else.
    private static Map<String, List<String>> checkGrantable(String platform, Map<String, List<String>> declared) {
        if (platform != null && !"SERVER".equalsIgnoreCase(platform)) {
            throw new CapabilityRejectedException("Not supported yet: only SERVER capabilities can be generated "
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
