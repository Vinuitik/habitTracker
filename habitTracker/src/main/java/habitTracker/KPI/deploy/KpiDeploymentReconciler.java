package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.CapabilityStatus;
import habitTracker.KPI.CollectorBundle;
import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.RunnerConnected;
import habitTracker.relay.RunnerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Converges every KpiDeployment whose status != its target (kpi-multidevice.md "Hub data model"; desired state).
 * Triggers: RunnerConnected (that device only, on a background thread: the uplink thread must stay free to read
 * replies) and a sweep every kpi.deploy.sweep-ms (5 min).
 *
 * target ACTIVE: backfill os → install the device's resolved manifest + files → approve with the runner-computed
 *   contractHash → (runner may answer awaiting_confirm/installing: rechecked next pass via GET /v1/collectors
 *   requiresState) → activate → ACTIVE. Only for a capability the user approved or that is live.
 * target REMOVED: DELETE the collector → REMOVED. Skipped (just marked) when a newer-version row on the same device
 *   still targets ACTIVE: the collector id "kpi-&lt;kpiId&gt;" is shared by every version, deleting it would kill the new one.
 * Offline runner: untouched, no attempt. Runner error: attempts++, backoff 1m/5m/30m/2h/6h, 5th failure → FAILED
 *   (parked until KpiDeploymentController retry). Redefine: when a row becomes ACTIVE, older-version rows on that
 *   device flip to target REMOVED.
 * Single node, in-process; runner install is idempotent by (collectorId, contractHash).
 */
@Component
public class KpiDeploymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(KpiDeploymentReconciler.class);
    static final int MAX_ATTEMPTS = 5;
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KpiDeploymentRepository deployments;
    private final KPIProxyCapabilityRepository capabilities;
    private final KPIRepository kpis;
    private final RunnerCollectorClient client;
    private final Executor background;
    private final Clock clock;
    private KpiCollectorDrain drain;

    @Autowired
    public KpiDeploymentReconciler(KpiDeploymentRepository deployments, KPIProxyCapabilityRepository capabilities,
                                   KPIRepository kpis, RunnerCollectorClient client) {
        this(deployments, capabilities, kpis, client,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "kpi-deploy-reconciler");
                    t.setDaemon(true);
                    return t;
                }), Clock.systemUTC());
    }

    KpiDeploymentReconciler(KpiDeploymentRepository deployments, KPIProxyCapabilityRepository capabilities,
                            KPIRepository kpis, RunnerCollectorClient client, Executor background, Clock clock) {
        this.deployments = deployments;
        this.capabilities = capabilities;
        this.kpis = kpis;
        this.client = client;
        this.background = background;
        this.clock = clock;
    }

    /** Setter-injected so test constructors stay unchanged; null = no drain (unit tests). */
    @Autowired(required = false)
    void setDrain(KpiCollectorDrain drain) {
        this.drain = drain;
    }

    // ── triggers ─────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${kpi.deploy.sweep-ms:300000}", initialDelayString = "${kpi.deploy.sweep-ms:300000}")
    public void sweep() {
        try {
            reconcile(deployments.findUnconverged());
        } catch (RuntimeException e) {
            log.warn("[KpiDeploy] sweep failed: {}", e.toString());
        }
    }

    @EventListener
    public void onRunnerConnected(RunnerConnected event) {
        kick(event.deviceId());
    }

    /** Converge one device soon, off the caller's thread. */
    public void kick(String deviceId) {
        background.execute(() -> {
            try {
                reconcile(deployments.findUnconvergedByDeviceId(deviceId));
            } catch (RuntimeException e) {
                log.warn("[KpiDeploy] reconcile of device {} failed: {}", deviceId, e.toString());
            }
        });
    }

    // ── convergence ──────────────────────────────────────────────────────────

    synchronized void reconcile(List<KpiDeployment> rows) {
        Set<String> offline = new HashSet<>();
        // newest version first: a new version going ACTIVE flips the older rows before they would be touched
        rows.stream().sorted(Comparator.comparing((KpiDeployment d) -> d.getCapabilityVersion() == null ? 0 : d.getCapabilityVersion()).reversed())
                .forEach(stale -> {
                    if (offline.contains(stale.getDeviceId())) return;
                    KpiDeployment dep = deployments.findById(stale.getId()).orElse(null); // may have changed meanwhile
                    if (dep == null) return;
                    try {
                        step(dep);
                    } catch (RunnerUnavailableException e) {
                        offline.add(dep.getDeviceId()); // not an attempt
                    } catch (RunnerCallException e) {
                        failed(dep, "Your runner refused it (" + runnerError(e) + ").");
                    } catch (IllegalArgumentException e) {
                        failed(dep, clip(e.getMessage()));
                    } catch (RuntimeException e) {
                        log.warn("[KpiDeploy] {} on {} threw: {}", dep.getId(), dep.getDeviceId(), e.toString());
                        failed(dep, "Something unexpected went wrong while setting this up.");
                    }
                });
    }

    private void step(KpiDeployment dep) {
        if (dep.getStatus() == KpiDeploymentStatus.FAILED) return;
        if (dep.getTarget() == KpiDeploymentTarget.ACTIVE && dep.getStatus() == KpiDeploymentStatus.ACTIVE) {
            rollOldVersions(dep);
            return;
        }
        if (dep.getTarget() == KpiDeploymentTarget.REMOVED && dep.getStatus() == KpiDeploymentStatus.REMOVED) return;
        if (dep.getNextAttemptAt() != null && clock.instant().isBefore(dep.getNextAttemptAt())) return;
        if (dep.getTarget() == KpiDeploymentTarget.REMOVED) remove(dep);
        else converge(dep);
    }

    private void converge(KpiDeployment dep) {
        KPIProxyCapability cap = dep.getCapabilityId() == null ? null : capabilities.findById(dep.getCapabilityId()).orElse(null);
        if (cap == null || cap.getCollectorBundle() == null || !deployable(cap)) return; // the build pipeline / user owns it
        KPI kpi = kpis.findById(dep.getKpiId()).orElse(null);
        if (kpi == null) return;
        String user = dep.getUserId(), device = dep.getDeviceId(), id = dep.getCollectorId();

        boolean waiting = (dep.getStatus() == KpiDeploymentStatus.INSTALLING || dep.getStatus() == KpiDeploymentStatus.AWAITING_CONFIRM)
                && dep.isApproved() && dep.getContractHash() != null;
        if (waiting) {
            JsonNode mine = find(client.list(user, device), id);
            if (mine != null) {
                switch (mine.path("requiresState").asText("none")) {
                    case "awaiting_confirm" -> { setStatus(dep, KpiDeploymentStatus.AWAITING_CONFIRM); return; }
                    case "installing" -> { setStatus(dep, KpiDeploymentStatus.INSTALLING); return; }
                    case "failed" -> { failed(dep, "A program this KPI needs could not be installed on that device."); return; }
                    default -> { activate(dep); return; }
                }
            }
            dep.setStatus(KpiDeploymentStatus.PENDING); // collector vanished from the runner: start again
        }

        CollectorBundle bundle = CollectorBundle.fromJson(cap.getCollectorBundle());
        if (dep.getOs() == null) {
            dep.setOs(client.info(user, device).os());
            save(dep);
        }
        if (dep.getOs() == null && !bundle.isLegacy()) {
            failed(dep, "That device's runner is too old to say which system it runs. Update the Relay app.");
            return;
        }
        JsonNode installed = client.install(user, device, id, kpi.getName(), bundle.resolve(dep.getOs()), bundle.files());
        dep.setContractHash(installed.path("contractHash").asText(null));
        save(dep);
        if (dep.getContractHash() == null) {
            failed(dep, "Your runner did not return a fingerprint for the collector.");
            return;
        }
        JsonNode approved = client.approve(user, device, id, dep.getContractHash());
        dep.setApproved(true);
        switch (approved == null ? "" : approved.path("state").asText("")) {
            case "awaiting_confirm" -> setStatus(dep, KpiDeploymentStatus.AWAITING_CONFIRM);
            case "installing" -> setStatus(dep, KpiDeploymentStatus.INSTALLING);
            default -> activate(dep);
        }
    }

    /** The user approved this capability's contract, or it is live. Dead versions are never (re)installed. */
    private static boolean deployable(KPIProxyCapability cap) {
        CapabilityStatus s = cap.getStatus();
        if (s == CapabilityStatus.DISABLED || s == CapabilityStatus.REJECTED || s == CapabilityStatus.RETIRED
                || s == CapabilityStatus.TEST_FAILED) return false;
        return s == CapabilityStatus.ACTIVE || s == CapabilityStatus.SHADOW || cap.getApprovedContractHash() != null;
    }

    private void activate(KpiDeployment dep) {
        client.activate(dep.getUserId(), dep.getDeviceId(), dep.getCollectorId());
        dep.setActivated(true);
        dep.setApproved(true);
        dep.setStatus(KpiDeploymentStatus.ACTIVE);
        dep.setAttempts(0);
        dep.setNextAttemptAt(null);
        dep.setLastError(null);
        save(dep);
        rollOldVersions(dep);
    }

    /** Redefine rule: older versions on this device stop being wanted only now that this one runs. */
    private void rollOldVersions(KpiDeployment active) {
        for (KpiDeployment old : deployments.findByKpiId(active.getKpiId())) {
            if (old.getId().equals(active.getId()) || !active.getDeviceId().equals(old.getDeviceId())) continue;
            if (old.getTarget() != KpiDeploymentTarget.ACTIVE) continue;
            if (old.getCapabilityVersion() == null || active.getCapabilityVersion() == null
                    || old.getCapabilityVersion() >= active.getCapabilityVersion()) continue;
            old.setTarget(KpiDeploymentTarget.REMOVED);
            old.setAttempts(0);
            old.setNextAttemptAt(null);
            save(old);
        }
    }

    private void remove(KpiDeployment dep) {
        // only legacy rows share an id across versions; per-version ids (kpi-<id>-v<N>) are deleted for real
        boolean shared = deployments.findByKpiId(dep.getKpiId()).stream().anyMatch(o ->
                !o.getId().equals(dep.getId()) && dep.getDeviceId().equals(o.getDeviceId())
                        && o.getTarget() == KpiDeploymentTarget.ACTIVE
                        && dep.getCollectorId() != null && dep.getCollectorId().equals(o.getCollectorId()));
        if (!shared && dep.getCollectorId() != null) {
            if (drain != null) drain.drain(dep); // offline/failed: throws, so nothing is deleted and the row stays REMOVING
            try {
                client.delete(dep.getUserId(), dep.getDeviceId(), dep.getCollectorId());
            } catch (RunnerCallException e) {
                if (e.getStatus() != 404) throw e; // already gone is fine
            }
        }
        dep.setActivated(false);
        dep.setApproved(false);
        dep.setStatus(KpiDeploymentStatus.REMOVED);
        dep.setAttempts(0);
        dep.setNextAttemptAt(null);
        dep.setLastError(null);
        save(dep);
    }

    private void failed(KpiDeployment dep, String sentence) {
        int attempts = dep.getAttempts() + 1;
        dep.setAttempts(attempts);
        dep.setLastError(sentence);
        if (attempts >= MAX_ATTEMPTS) {
            dep.setStatus(KpiDeploymentStatus.FAILED);
            dep.setNextAttemptAt(null);
        } else {
            dep.setStatus(dep.getTarget() == KpiDeploymentTarget.REMOVED ? KpiDeploymentStatus.REMOVING : KpiDeploymentStatus.PENDING);
            dep.setNextAttemptAt(clock.instant().plus(BACKOFF.get(attempts - 1)));
        }
        log.info("[KpiDeploy] {} on {} attempt {}: {}", dep.getCollectorId(), dep.getDeviceId(), attempts, sentence);
        save(dep);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setStatus(KpiDeployment dep, KpiDeploymentStatus s) {
        dep.setStatus(s);
        dep.setLastError(null);
        save(dep);
    }

    private void save(KpiDeployment dep) {
        dep.setUpdatedAt(Instant.now(clock));
        deployments.save(dep);
    }

    private static JsonNode find(JsonNode list, String id) {
        if (list == null || !list.isArray()) return null;
        for (JsonNode n : list) if (id.equals(n.path("id").asText())) return n;
        return null;
    }

    private static String runnerError(RunnerCallException e) {
        try {
            String msg = JSON.readTree(e.getBody()).path("error").asText("");
            if (!msg.isBlank()) return clip(msg);
        } catch (Exception ignored) {
            // not JSON
        }
        return "HTTP " + e.getStatus();
    }

    private static String clip(String s) {
        if (s == null) return "Something went wrong.";
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
