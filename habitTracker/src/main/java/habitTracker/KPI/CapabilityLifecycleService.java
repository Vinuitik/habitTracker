package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Post-test-gate state machine, platform-agnostic (kpi-sensors.md "Test pipeline" steps 5-7). Knows nothing about
 * the runner: a platform's test gate hands over a sample and this class moves the version through
 *   (test gate passed) -> AWAITING_CONFIRM -> SHADOW -> ACTIVE
 * "Looks right" = confirmRight(); "Wrong" is handled by the caller (it needs the generator) which only uses
 * recordWrong() for the round bookkeeping. A repair of an already live capability skips the confirm step
 * (testGatePassed(.., skipConfirm=true)).
 *
 * Wiring the KPI to the capability (proxyType/proxyStatus) happens when the version first goes live (SHADOW or
 * ACTIVE), so the nightly fill starts producing flagged readings during SHADOW.
 */
@Service
public class CapabilityLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityLifecycleService.class);

    public static final int MAX_CONFIRM_ROUNDS = 3;

    private final KPIProxyCapabilityRepository repository;
    private final KPIRepository kpiRepository;
    private final int shadowDays;
    private final ApplicationEventPublisher events;

    public CapabilityLifecycleService(KPIProxyCapabilityRepository repository, KPIRepository kpiRepository,
                                      @Value("${kpi.shadow.days:7}") int shadowDays, ApplicationEventPublisher events) {
        this.repository = repository;
        this.kpiRepository = kpiRepository;
        this.shadowDays = shadowDays;
        this.events = events;
    }

    /**
     * A test gate passed with this sample. First builds stop at AWAITING_CONFIRM; repairs (skipConfirm) go straight
     * live: back into SHADOW if the version they replace was still on trial, else ACTIVE.
     */
    public KPIProxyCapability testGatePassed(KPIProxyCapability cap, KPI kpi, CapabilityOutput out, LocalDate sampleDate,
                                             boolean skipConfirm) {
        cap.setFailureReason(null);
        cap.setLastRunAt(LocalDateTime.now());
        cap.setLastResult("Test gate OK for " + sampleDate + ": " + CapabilityOutput.formatValue(out.value(), out.unit()));
        cap.setLastUnit(out.unit());
        cap.setConsecutiveFailures(0);
        cap.setSampleValue(out.value());
        cap.setSampleUnit(out.unit());
        cap.setSampleEvidence(out.evidence());
        cap.setSampleDate(sampleDate);
        if (!skipConfirm) {
            cap.setStatus(CapabilityStatus.AWAITING_CONFIRM);
            return repository.save(cap);
        }
        boolean stillOnTrial = cap.getShadowUntil() != null && cap.getShadowUntil().isAfter(LocalDate.now());
        cap.setStatus(stillOnTrial ? CapabilityStatus.SHADOW : CapabilityStatus.ACTIVE);
        repository.save(cap);
        wire(cap, kpi);
        return cap;
    }

    /** "Looks right": AWAITING_CONFIRM -> SHADOW for shadowDays days (ACTIVE straight away when that is 0). */
    public KPIProxyCapability confirmRight(KPIProxyCapability cap, KPI kpi) {
        if (cap.getStatus() != CapabilityStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException("Only a capability waiting for confirmation can be confirmed (status "
                    + cap.getStatus() + ")");
        }
        if (shadowDays > 0) {
            cap.setStatus(CapabilityStatus.SHADOW);
            cap.setShadowUntil(LocalDate.now().plusDays(shadowDays));
        } else {
            cap.setStatus(CapabilityStatus.ACTIVE);
        }
        repository.save(cap);
        wire(cap, kpi);
        return cap;
    }

    /**
     * Bookkeeping for a "Wrong" answer. Returns true when another round is allowed; false = stop (the caller marks
     * the version failed with giveUpSentence()).
     */
    public boolean recordWrong(KPIProxyCapability cap) {
        int rounds = (cap.getConfirmRounds() == null ? 0 : cap.getConfirmRounds()) + 1;
        cap.setConfirmRounds(rounds);
        repository.save(cap);
        return rounds < MAX_CONFIRM_ROUNDS;
    }

    public static String giveUpSentence() {
        return "You said the number was wrong " + MAX_CONFIRM_ROUNDS
                + " times, so I stopped. Start over and describe what to measure in more detail.";
    }

    /** What the next generation prompt is told about a "Wrong" answer. */
    public static String wrongFeedback(KPIProxyCapability cap, String reason) {
        StringBuilder sb = new StringBuilder("The script ran fine and printed ")
                .append(cap.getSampleValue() == null ? "a value" : CapabilityOutput.formatValue(cap.getSampleValue(), cap.getSampleUnit()))
                .append(cap.getSampleDate() == null ? "" : " for " + cap.getSampleDate()).append(", but the user says this number is WRONG.");
        if (cap.getSampleEvidence() != null && !cap.getSampleEvidence().isEmpty()) {
            sb.append(" Evidence it printed: ");
            cap.getSampleEvidence().stream().limit(CapabilityOutput.MAX_EVIDENCE)
                    .forEach(r -> sb.append(r.label()).append(" = ").append(r.value()).append("; "));
        }
        return sb.append("\nUser's reason: ").append(reason).toString();
    }

    /** SHADOW versions whose trial is over become ACTIVE. Nightly at 00:30 (after the 00:05 updater run). */
    @Scheduled(cron = "${kpi.shadow.promote-cron:0 30 0 * * ?}")
    public void promoteDueShadows() {
        promoteDueShadows(LocalDate.now());
    }

    /** → number promoted. Idempotent. */
    public int promoteDueShadows(LocalDate today) {
        List<KPIProxyCapability> due = repository.findByStatus(CapabilityStatus.SHADOW).stream()
                .filter(c -> c.getShadowUntil() == null || !c.getShadowUntil().isAfter(today)).toList();
        for (KPIProxyCapability c : due) {
            c.setStatus(CapabilityStatus.ACTIVE);
            repository.save(c);
            log.info("[CapabilityLifecycle] capability {} v{} finished its shadow period -> ACTIVE", c.getId(), c.getVersion());
        }
        return due.size();
    }

    /**
     * Redefine (R4): compared with the live version it replaces, did the unit or the source types change? Then old
     * and new history are not comparable and the chart marks the switch. Repairs keep the meaning, so never flag.
     */
    private void markDefinitionChange(KPIProxyCapability cap) {
        if (cap.getLiveSince() == null) cap.setLiveSince(LocalDate.now());
        if (Boolean.TRUE.equals(cap.getRepair())) {
            repository.save(cap);
            return;
        }
        KPIProxyCapability prev = repository.findByKpiIdAndUserId(cap.getKpiId(), cap.getUserId()).stream()
                .filter(o -> !o.getId().equals(cap.getId()))
                .filter(o -> o.getStatus() == CapabilityStatus.ACTIVE || o.getStatus() == CapabilityStatus.SHADOW
                        || o.getStatus() == CapabilityStatus.NEEDS_REPAIR)
                .max(java.util.Comparator.comparing(o -> o.getVersion() == null ? 0 : o.getVersion())).orElse(null);
        if (prev != null) {
            boolean unit = prev.getLastUnit() != null && cap.getLastUnit() != null
                    && !prev.getLastUnit().trim().equalsIgnoreCase(cap.getLastUnit().trim());
            boolean sources = !sourceTypes(prev).equals(sourceTypes(cap));
            cap.setMeaningChanged(unit || sources);
        }
        repository.save(cap);
    }

    private static java.util.Set<String> sourceTypes(KPIProxyCapability c) {
        List<String> l = c.getDeclaredCapabilities() == null ? null : c.getDeclaredCapabilities().get("sources");
        return l == null ? java.util.Set.of() : new java.util.HashSet<>(l);
    }

    /** Wires the KPI to the capability and retires older live versions. */
    public void wire(KPIProxyCapability cap, KPI kpi) {
        markDefinitionChange(cap);
        for (KPIProxyCapability other : repository.findByKpiIdAndUserId(cap.getKpiId(), cap.getUserId())) {
            if (!other.getId().equals(cap.getId())
                    && (other.getStatus() == CapabilityStatus.ACTIVE || other.getStatus() == CapabilityStatus.NEEDS_REPAIR
                    || other.getStatus() == CapabilityStatus.SHADOW)) {
                other.setStatus(CapabilityStatus.DISABLED);
                repository.save(other);
            }
        }
        KPI fresh = kpiRepository.findById(kpi.getId()).orElse(kpi);
        fresh.setProxyType(ProxyType.RUNNER_COLLECTOR); // RUNNER is the only generated platform
        fresh.setProxyStatus(ProxyStatus.ACTIVE);
        fresh.setConsecutiveProxyFailures(0);
        fresh.setUpdatedAt(LocalDateTime.now());
        kpiRepository.save(fresh);
        if (events != null) events.publishEvent(new CapabilityWentLive(cap));
    }
}
