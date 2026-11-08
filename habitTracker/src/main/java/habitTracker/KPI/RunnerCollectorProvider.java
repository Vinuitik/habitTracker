package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.DeviceCombiner;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import habitTracker.KPI.deploy.KpiDeviceReading;
import habitTracker.KPI.deploy.KpiDeviceReadingRepository;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.RunnerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
 * Per-device state (collector id, cursor, activation) lives on KpiDeployment rows; pull(kpi) iterates the live
 *   version's ACTIVE-target rows and hands each device's days to storeDeviceReadings (the aggregation seam).
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
    private final KpiDeploymentRepository deployments;
    private final RunnerCollectorClient client;
    private final ProxyHealthService health;
    private final ProxyReadingRecorder recorder;
    private final KpiDeviceReadingRepository deviceReadings;
    private final DynamicKPIDataRepository dataRepository;
    private final KPICollectionNameUtil collectionNames;

    public RunnerCollectorProvider(KPIRepository kpiRepository, KPIProxyCapabilityRepository repository,
                                   KpiDeploymentRepository deployments, RunnerCollectorClient client,
                                   ProxyHealthService health, ProxyReadingRecorder recorder,
                                   KpiDeviceReadingRepository deviceReadings, DynamicKPIDataRepository dataRepository,
                                   KPICollectionNameUtil collectionNames) {
        this.kpiRepository = kpiRepository;
        this.repository = repository;
        this.deployments = deployments;
        this.client = client;
        this.health = health;
        this.recorder = recorder;
        this.deviceReadings = deviceReadings;
        this.dataRepository = dataRepository;
        this.collectionNames = collectionNames;
    }

    @Scheduled(cron = "${kpi.runner.pull-cron:0 40 * * * ?}")
    public void pullDue() {
        LocalDate today = LocalDate.now();
        for (KPI kpi : kpiRepository.findByActive(true)) {
            if (kpi.getProxyType() != ProxyType.RUNNER_COLLECTOR || health.needsRepair(kpi)) continue;
            Optional<KPIProxyCapability> cap = live(kpi);
            if (cap.isEmpty()) continue;
            List<KpiDeployment> due = pullable(cap.get());
            if (due.isEmpty() || due.stream().allMatch(d -> today.equals(d.getLastPulledOn()))) continue;
            try {
                pull(kpi);
            } catch (RuntimeException e) { // one broken KPI must not stop the others
                log.warn("[RunnerPull] KPI id={} pull threw: {}", kpi.getId(), e.toString());
            }
        }
    }

    /**
     * One pull for this KPI (nightly/hourly job and the on-demand endpoint): every pullable deployment of the live
     * version, one after another. Never throws for runner problems. Overall outcome: FAILED if any device's collector
     * failed, else PULLED if any pulled, else SKIPPED_OFFLINE.
     */
    public PullResult pull(KPI kpi) {
        Optional<KPIProxyCapability> found = live(kpi);
        if (found.isEmpty()) return new PullResult(Outcome.NOT_RUNNABLE, 0);
        KPIProxyCapability cap = found.get();
        List<KpiDeployment> rows = pullable(cap);
        if (rows.isEmpty()) return new PullResult(Outcome.NOT_RUNNABLE, 0);
        Outcome overall = Outcome.SKIPPED_OFFLINE;
        int stored = 0;
        for (KpiDeployment dep : rows) {
            PullResult r = pullDeployment(kpi, cap, dep);
            stored += r.stored();
            if (r.outcome() == Outcome.FAILED) overall = Outcome.FAILED;
            else if (r.outcome() == Outcome.PULLED && overall != Outcome.FAILED) overall = Outcome.PULLED;
        }
        return new PullResult(overall, stored);
    }

    private PullResult pullDeployment(KPI kpi, KPIProxyCapability cap, KpiDeployment dep) {
        String user = cap.getUserId(), device = dep.getDeviceId(), id = dep.getCollectorId();
        try {
            if (!dep.isActivated()) {
                client.activate(user, device, id);
                dep.setActivated(true);
            }
            JsonNode state = find(client.list(user, device), id);
            if (state != null && "failed".equals(state.path("state").asText(""))) {
                String err = state.path("lastError").asText("");
                cap.setLastRunAt(LocalDateTime.now());
                cap.setLastResult("FAILED on runner: " + clip(err));
                cap.setConsecutiveFailures((cap.getConsecutiveFailures() == null ? 0 : cap.getConsecutiveFailures()) + 1);
                dep.setLastPulledOn(LocalDate.now());
                saveDeployment(dep);
                repository.save(cap);
                health.recordFailure(kpi); // the runner stopped scheduling it; breaker → M12 repair (new version + activate)
                return new PullResult(Outcome.FAILED, 0);
            }
            JsonNode readings = client.readings(user, device, id, dep.getReadingsSince());
            // one value per local day; a re-run of the same window (same date) keeps the newest recordedAt
            Map<LocalDate, JsonNode> byDay = new TreeMap<>();
            String maxRecorded = dep.getReadingsSince();
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
            int stored = storeDeviceReadings(kpi, cap, dep, byDay);
            dep.setReadingsSince(maxRecorded);
            dep.setLastPulledOn(LocalDate.now());
            saveDeployment(dep);
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

    /**
     * Aggregation seam: one device's per-day readings (newest recordedAt per day, already deduped) land in
     * kpi_device_readings (unique kpiId+deviceId+date, newest recordedAt wins), then every touched date's combined
     * value over the live version's ACTIVE-target devices is re-recorded through ProxyReadingRecorder (SHADOW /
     * plausibility / breaker intact). devicesReported/devicesExpected are set only when expected > 1; a MANUAL point
     * is never overwritten. A late backfill from any device simply re-records that date.
     * @return days stored
     */
    int storeDeviceReadings(KPI kpi, KPIProxyCapability cap, KpiDeployment dep, Map<LocalDate, JsonNode> byDay) {
        List<LocalDate> touched = new java.util.ArrayList<>();
        for (Map.Entry<LocalDate, JsonNode> e : byDay.entrySet()) {
            JsonNode r = e.getValue();
            String unit = r.path("unit").asText(null);
            if (unit != null && !unit.isBlank()) cap.setLastUnit(unit);
            String at = r.path("recordedAt").asText(null);
            KpiDeviceReading row = deviceReadings.findByKpiIdAndDeviceIdAndDate(kpi.getId(), dep.getDeviceId(), e.getKey())
                    .orElse(null);
            if (row != null && orMin(parse(at)).isBefore(orMin(parse(row.getRecordedAt())))) continue; // stale re-delivery
            if (row == null) {
                row = KpiDeviceReading.builder().userId(kpi.getUserId()).kpiId(kpi.getId())
                        .deviceId(dep.getDeviceId()).date(e.getKey()).build();
            }
            row.setCapabilityVersion(cap.getVersion());
            row.setValue(r.path("value").asDouble());
            row.setUnit(unit);
            row.setRecordedAt(at);
            deviceReadings.save(row);
            touched.add(e.getKey());
        }
        Set<String> expectedDevices = pullable(cap).stream().map(KpiDeployment::getDeviceId).collect(Collectors.toSet());
        int expected = expectedDevices.size();
        String collection = collectionNames.toCollectionName(kpi.getId());
        int stored = 0;
        for (LocalDate day : touched) {
            List<KpiDeviceReading> rows = deviceReadings.findByKpiIdAndDate(kpi.getId(), day).stream()
                    .filter(x -> expectedDevices.contains(x.getDeviceId()))
                    .sorted(Comparator.comparing(x -> orMin(parse(x.getRecordedAt()))))
                    .toList();
            if (rows.isEmpty()) continue;
            KPIData existing = dataRepository.findByDate(day, collection).orElse(null);
            if (existing != null && existing.getSource() == KPIDataSource.MANUAL) continue; // never overwrite a human
            double combined = DeviceCombiner.combine(cap.getCombine(), rows.stream().map(KpiDeviceReading::getValue).toList());
            String unit = rows.get(rows.size() - 1).getUnit();
            ProxyProvider.Context ctx = new ProxyProvider.Context(cap.getStatus() == CapabilityStatus.SHADOW, cap.getLastUnit());
            recorder.record(kpi, day, combined, ctx, KPIDataSource.PROXY_CAPABILITY);
            stored++;
            cap.setLastResult("OK: " + CapabilityOutput.formatValue(combined, unit) + " for " + day);
            stampDevices(collection, day, existing, expected > 1 ? rows.size() : null, expected > 1 ? expected : null);
            if (health.needsRepair(kpi)) break; // anomaly tripped the breaker: stop storing; repair takes over
        }
        return stored;
    }

    /** Sets devicesReported/Expected on the day's point (null clears stale values). No write on the single-device path. */
    private void stampDevices(String collection, LocalDate day, KPIData before, Integer reported, Integer expected) {
        if (reported == null && (before == null || (before.getDevicesReported() == null && before.getDevicesExpected() == null))) {
            return;
        }
        dataRepository.findByDate(day, collection).ifPresent(d -> {
            d.setDevicesReported(reported);
            d.setDevicesExpected(expected);
            dataRepository.save(d, collection);
        });
    }

    /** POST /activate on every device when a RUNNER version goes live (SHADOW or ACTIVE). Best effort: pull() retries. */
    @EventListener
    public void onLive(CapabilityWentLive event) {
        KPIProxyCapability cap = event.capability();
        if (cap.getPlatform() != CapabilityPlatform.RUNNER) return;
        for (KpiDeployment dep : pullable(cap)) {
            try {
                client.activate(cap.getUserId(), dep.getDeviceId(), dep.getCollectorId());
                dep.setActivated(true);
            } catch (RuntimeException e) {
                dep.setActivated(false);
                log.info("[RunnerPull] activate of {} on {} failed ({}), the next pull retries", dep.getCollectorId(),
                        dep.getDeviceId(), e.toString());
            }
            saveDeployment(dep);
        }
    }

    private void saveDeployment(KpiDeployment dep) {
        dep.setUpdatedAt(Instant.now());
        deployments.save(dep);
    }

    /** Deployments of this capability version that should be running (target ACTIVE) and have a collector id. */
    private List<KpiDeployment> pullable(KPIProxyCapability cap) {
        return deployments.findByKpiIdAndCapabilityVersion(cap.getKpiId(), cap.getVersion()).stream()
                .filter(d -> d.getTarget() == KpiDeploymentTarget.ACTIVE)
                .filter(d -> d.getDeviceId() != null && d.getCollectorId() != null)
                .toList();
    }

    private Optional<KPIProxyCapability> live(KPI kpi) {
        return repository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), kpi.getUserId())
                .filter(c -> c.getPlatform() == CapabilityPlatform.RUNNER)
                .filter(c -> c.getStatus() == CapabilityStatus.ACTIVE || c.getStatus() == CapabilityStatus.SHADOW);
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
