package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.RunnerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * S3: pulls RUNNER collector readings into the KPI (kpi-sensors.md "Readings flow: the hub pulls").
 * Not a ProxyProvider: the nightly fill asks for one value per day and treats "nothing" as a failure, while a runner
 * keeps readings locally and may be offline for days. Instead:
 *   pullDue() @Scheduled kpi.runner.pull-cron (hourly at :40) → every active KPI with proxyType RUNNER_COLLECTOR,
 *     breaker closed, not yet pulled today → pull(kpi). Hourly = "when the runner comes back online" within an hour
 *     without a presence hook in relay.
 *   pull(kpi) → latest version must be SHADOW/ACTIVE + RUNNER → POST /activate if not yet confirmed
 *     → GET /v1/collectors: our collector state "failed" → ProxyHealthService.recordFailure (breaker → M12 repair)
 *     → GET .../readings?since=<readingsSince> (runner filters on recordedAt) → per local day (from's date; a day
 *       recorded twice keeps the newest) → ProxyReadingRecorder.record (SHADOW / plausibility / anomaly)
 *       → readingsSince = max recordedAt, lastPulledOn = today.
 *   Runner offline / unreachable → skipped, nothing counted (no breaker trip); tried again next hour.
 * Activation listener: CapabilityWentLive for a RUNNER version → POST /activate (best effort; the pull retries).
 */
@Component
public class RunnerCollectorProvider {

    private static final Logger log = LoggerFactory.getLogger(RunnerCollectorProvider.class);

    public enum Outcome { PULLED, SKIPPED_OFFLINE, NOT_RUNNABLE, FAILED }

    public record PullResult(Outcome outcome, int stored) {}

    private final KPIRepository kpiRepository;
    private final KPIProxyCapabilityRepository repository;
    private final RunnerCollectorClient client;
    private final ProxyHealthService health;
    private final ProxyReadingRecorder recorder;

    public RunnerCollectorProvider(KPIRepository kpiRepository, KPIProxyCapabilityRepository repository,
                                   RunnerCollectorClient client, ProxyHealthService health, ProxyReadingRecorder recorder) {
        this.kpiRepository = kpiRepository;
        this.repository = repository;
        this.client = client;
        this.health = health;
        this.recorder = recorder;
    }

    @Scheduled(cron = "${kpi.runner.pull-cron:0 40 * * * ?}")
    public void pullDue() {
        LocalDate today = LocalDate.now();
        for (KPI kpi : kpiRepository.findByActive(true)) {
            if (kpi.getProxyType() != ProxyType.RUNNER_COLLECTOR || health.needsRepair(kpi)) continue;
            Optional<KPIProxyCapability> cap = live(kpi);
            if (cap.isEmpty() || today.equals(cap.get().getLastPulledOn())) continue;
            try {
                pull(kpi);
            } catch (RuntimeException e) { // one broken KPI must not stop the others
                log.warn("[RunnerPull] KPI id={} pull threw: {}", kpi.getId(), e.toString());
            }
        }
    }

    /** One pull for this KPI (nightly/hourly job and the on-demand endpoint). Never throws for runner problems. */
    public PullResult pull(KPI kpi) {
        Optional<KPIProxyCapability> found = live(kpi);
        if (found.isEmpty()) return new PullResult(Outcome.NOT_RUNNABLE, 0);
        KPIProxyCapability cap = found.get();
        String user = cap.getUserId(), device = cap.getRunnerDeviceId(), id = cap.getCollectorId();
        try {
            if (!Boolean.TRUE.equals(cap.getCollectorActivated())) {
                client.activate(user, device, id);
                cap.setCollectorActivated(true);
            }
            JsonNode state = find(client.list(user, device), id);
            if (state != null && "failed".equals(state.path("state").asText(""))) {
                String err = state.path("lastError").asText("");
                cap.setLastRunAt(LocalDateTime.now());
                cap.setLastResult("FAILED on runner: " + clip(err));
                cap.setConsecutiveFailures((cap.getConsecutiveFailures() == null ? 0 : cap.getConsecutiveFailures()) + 1);
                cap.setLastPulledOn(LocalDate.now());
                repository.save(cap);
                health.recordFailure(kpi); // the runner stopped scheduling it; breaker → M12 repair (new version + activate)
                return new PullResult(Outcome.FAILED, 0);
            }
            JsonNode readings = client.readings(user, device, id, cap.getReadingsSince());
            // one value per local day; a re-run of the same window (same date) keeps the newest recordedAt
            Map<LocalDate, JsonNode> byDay = new TreeMap<>();
            String maxRecorded = cap.getReadingsSince();
            OffsetDateTime maxAt = parse(maxRecorded);
            for (JsonNode r : readings) {
                OffsetDateTime from = parse(r.path("from").asText(null));
                if (from == null || !r.path("value").isNumber() || !Double.isFinite(r.path("value").asDouble())) continue;
                LocalDate day = from.toLocalDate();
                JsonNode prev = byDay.get(day);
                OffsetDateTime at = parse(r.path("recordedAt").asText(null));
                if (prev == null || (at != null && !at.isBefore(orMin(parse(prev.path("recordedAt").asText(null)))))) {
                    byDay.put(day, r);
                }
                if (at != null && (maxAt == null || at.isAfter(maxAt))) {
                    maxAt = at;
                    maxRecorded = r.path("recordedAt").asText();
                }
            }
            int stored = 0;
            for (Map.Entry<LocalDate, JsonNode> e : byDay.entrySet()) {
                JsonNode r = e.getValue();
                String unit = r.path("unit").asText(null);
                if (unit != null && !unit.isBlank()) cap.setLastUnit(unit);
                ProxyProvider.Context ctx = new ProxyProvider.Context(cap.getStatus() == CapabilityStatus.SHADOW, cap.getLastUnit());
                recorder.record(kpi, e.getKey(), r.path("value").asDouble(), ctx, KPIDataSource.PROXY_CAPABILITY);
                stored++;
                cap.setLastResult("OK: " + CapabilityOutput.formatValue(r.path("value").asDouble(), unit) + " for " + e.getKey());
                if (health.needsRepair(kpi)) break; // anomaly tripped the breaker: stop storing; repair takes over
            }
            cap.setReadingsSince(maxRecorded);
            cap.setLastPulledOn(LocalDate.now());
            if (stored > 0) {
                cap.setLastRunAt(LocalDateTime.now());
                cap.setConsecutiveFailures(0);
            }
            repository.save(cap);
            return new PullResult(Outcome.PULLED, stored);
        } catch (RunnerUnavailableException e) {
            log.info("[RunnerPull] KPI id={}: runner {} offline, skipped", kpi.getId(), device);
            return new PullResult(Outcome.SKIPPED_OFFLINE, 0);
        } catch (RunnerCallException e) {
            // runner up but refused (old runner without collectors, 404 after a data-dir wipe...): log, retry next hour
            log.warn("[RunnerPull] KPI id={}: runner answered {}: {}", kpi.getId(), e.getStatus(), clip(e.getBody()));
            cap.setLastResult("PULL_ERROR: HTTP " + e.getStatus());
            repository.save(cap);
            return new PullResult(Outcome.SKIPPED_OFFLINE, 0);
        }
    }

    /** POST /activate when a RUNNER version goes live (SHADOW or ACTIVE). Best effort: pull() retries. */
    @EventListener
    public void onLive(CapabilityWentLive event) {
        KPIProxyCapability cap = event.capability();
        if (cap.getPlatform() != CapabilityPlatform.RUNNER || cap.getRunnerDeviceId() == null || cap.getCollectorId() == null) return;
        try {
            client.activate(cap.getUserId(), cap.getRunnerDeviceId(), cap.getCollectorId());
            cap.setCollectorActivated(true);
        } catch (RuntimeException e) {
            cap.setCollectorActivated(false);
            log.info("[RunnerPull] activate of {} on {} failed ({}), the next pull retries", cap.getCollectorId(),
                    cap.getRunnerDeviceId(), e.toString());
        }
        repository.save(cap);
    }

    private Optional<KPIProxyCapability> live(KPI kpi) {
        return repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), kpi.getUserId())
                .filter(c -> c.getPlatform() == CapabilityPlatform.RUNNER)
                .filter(c -> c.getStatus() == CapabilityStatus.ACTIVE || c.getStatus() == CapabilityStatus.SHADOW)
                .filter(c -> c.getRunnerDeviceId() != null && c.getCollectorId() != null);
    }

    private static JsonNode find(JsonNode list, String id) {
        if (list == null || !list.isArray()) return null;
        for (JsonNode c : list) if (id.equals(c.path("id").asText())) return c;
        return null;
    }

    private static OffsetDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime orMin(OffsetDateTime t) {
        return t == null ? OffsetDateTime.MIN : t;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
