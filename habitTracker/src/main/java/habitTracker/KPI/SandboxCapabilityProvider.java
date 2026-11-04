package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * M7 provider for ProxyType.SANDBOX_CAPABILITY: loads the KPI's stored KPIProxyCapability from
 * Mongo, asks the sandbox sidecar to run it, and parses the LAST non-blank stdout line (plain number or {value, unit?, evidence?} JSON).
 * Any failure (no/inactive capability, timeout, non-zero exit, sidecar down, unparseable output)
 * returns Optional.empty(), which KPIProxyFillService turns into
 * ProxyHealthService.recordFailure (M12 circuit breaker). Never throws.
 *
 * The script sees a module-level TARGET_DATE ISO string. It gets network only to its approved declared
 * hosts (via the egress proxy) and only its own declared secrets as env vars; nothing else.
 */
@Component
public class SandboxCapabilityProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxCapabilityProvider.class);

    private final KPIProxyCapabilityRepository repository;
    private final SandboxClient sandboxClient;
    private final CapabilitySecretService secretService;
    private final int timeoutSeconds;

    public SandboxCapabilityProvider(KPIProxyCapabilityRepository repository, SandboxClient sandboxClient,
                                     CapabilitySecretService secretService,
                                     @Value("${sandbox.script-timeout-seconds:30}") int timeoutSeconds) {
        this.repository = repository;
        this.sandboxClient = sandboxClient;
        this.secretService = secretService;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ProxyType getType() {
        return ProxyType.SANDBOX_CAPABILITY;
    }

    @Override
    public Optional<Double> fetchValue(KPI kpi, LocalDate date) {
        Optional<KPIProxyCapability> found = repository
                .findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), kpi.getUserId());
        if (found.isEmpty()) {
            log.warn("[Sandbox] no capability stored for KPI id={} user={}", kpi.getId(), kpi.getUserId());
            return Optional.empty();
        }
        KPIProxyCapability cap = found.get();
        if ((cap.getStatus() != CapabilityStatus.ACTIVE && cap.getStatus() != CapabilityStatus.SHADOW)
                || cap.getPlatform() != CapabilityPlatform.SERVER
                || cap.getSourceCode() == null || cap.getSourceCode().isBlank()) {
            log.warn("[Sandbox] capability {} not runnable (status={} platform={})",
                    cap.getId(), cap.getStatus(), cap.getPlatform());
            return Optional.empty();
        }

        String code = "TARGET_DATE = \"" + date + "\"\n" + cap.getSourceCode();
        SandboxClient.Request request;
        try {
            request = secretService.requestFor(cap, kpi.getUserId(), code, timeoutSeconds);
        } catch (MissingSecretException | CapabilityRejectedException e) {
            record(cap, "NOT_RUN: " + e.getMessage(), false);
            return Optional.empty();
        }
        SandboxClient.Result result = sandboxClient.run(request);

        CapabilityOutput out = result.ok() ? CapabilityOutput.parse(result.stdout()) : null;
        Double value = out == null ? null : out.value();
        if (value != null) {
            cap.setLastUnit(out.unit());
            record(cap, "OK: " + CapabilityOutput.formatValue(value, out.unit()), true);
        } else {
            String detail = result.stderr() == null || result.stderr().isBlank() ? result.stdout() : result.stderr();
            record(cap, result.outcome() + (result.exitCode() != null ? " exit=" + result.exitCode() : "")
                    + ": " + truncate(detail), false);
        }
        return Optional.ofNullable(value);
    }

    private void record(KPIProxyCapability cap, String lastResult, boolean success) {
        cap.setLastRunAt(LocalDateTime.now());
        cap.setLastResult(lastResult);
        int prev = cap.getConsecutiveFailures() != null ? cap.getConsecutiveFailures() : 0;
        cap.setConsecutiveFailures(success ? 0 : prev + 1);
        try {
            repository.save(cap);
        } catch (Exception e) {
            log.warn("[Sandbox] could not persist run result for capability {}: {}", cap.getId(), e.toString());
        }
        if (!success) {
            log.warn("[Sandbox] capability {} failed: {}", cap.getId(), lastResult);
        }
    }

    /** Value of the last stdout line: a plain number or the {value, unit?, evidence?} JSON (see CapabilityOutput). */
    static Double parseLastLine(String stdout) {
        CapabilityOutput o = CapabilityOutput.parse(stdout);
        return o == null ? null : o.value();
    }

    @Override
    public Context context(KPI kpi) {
        return repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), kpi.getUserId())
                .map(c -> new Context(c.getStatus() == CapabilityStatus.SHADOW, c.getLastUnit()))
                .orElse(Context.NONE);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
