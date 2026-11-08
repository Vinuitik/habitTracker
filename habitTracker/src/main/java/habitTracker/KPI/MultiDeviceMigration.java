package habitTracker.KPI;

import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.KpiDeploymentStatus;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * One-time move to the multi-device model (docs/designs/kpi-multidevice.md). Idempotent; the `_migration` doc
 * {_id: MIGRATION_ID} marks it done, and a deployment row is only inserted when (kpiId, deviceId, version) has none.
 * Reads/writes capabilities RAW: the per-device fields (runnerDeviceId, collectorId, readingsSince, lastPulledOn,
 * collectorActivated) no longer exist on KPIProxyCapability, so the mapped class cannot see them.
 *   every RUNNER capability with runnerDeviceId → one KpiDeployment carrying its old per-device fields;
 *     capability.targetDeviceIds = [that device], combine "sum", old fields $unset;
 *     the legacy bundle becomes a variants bundle keyed by the device's os when that is known (device document field
 *     "os", or an existing deployment's os), otherwise stays legacy (CollectorBundle.resolve handles both).
 * The row is target ACTIVE only for the latest version of a KPI that is live or in flight; older versions get
 * target/status REMOVED so a reconciler never resurrects superseded collectors.
 */
@Component("multiDeviceMigration")
@DependsOn("runnerOnlyKpiMigration")
public class MultiDeviceMigration {

    private static final Logger log = LoggerFactory.getLogger(MultiDeviceMigration.class);

    static final String MIGRATION_ID = "multi-device-v1";
    static final String CAPABILITIES = "kpi_proxy_capabilities";
    static final String DEVICES = "relay_devices";
    static final String MIGRATION_COLLECTION = "_migration";
    private static final List<String> OLD_FIELDS = List.of("runnerDeviceId", "collectorId", "readingsSince", "lastPulledOn", "collectorActivated");
    private static final Set<String> LIVE_OR_IN_FLIGHT = Set.of("ACTIVE", "SHADOW", "PENDING_TEST", "AWAITING_CONFIRM",
            "AWAITING_SENSOR", "AWAITING_CONTRACT", "NEEDS_REPAIR");

    private final MongoTemplate mongo;
    private final KpiDeploymentRepository deployments;

    public MultiDeviceMigration(MongoTemplate mongo, KpiDeploymentRepository deployments) {
        this.mongo = mongo;
        this.deployments = deployments;
    }

    /** @return true when this call did the work, false when the flag says it was already done. */
    @PostConstruct
    public boolean migrate() {
        if (mongo.exists(Query.query(Criteria.where("_id").is(MIGRATION_ID)), MIGRATION_COLLECTION)) {
            return false;
        }
        int rows = 0, rewritten = 0;
        // a missing platform field reads back as the RUNNER default; an explicit null is a retired ex-SERVER document
        Query withDevice = Query.query(new Criteria().andOperator(
                new Criteria().orOperator(Criteria.where("platform").is("RUNNER"), Criteria.where("platform").exists(false)),
                Criteria.where("runnerDeviceId").ne(null)));
        for (Document cap : mongo.find(withDevice, Document.class, CAPABILITIES)) {
            String device = cap.getString("runnerDeviceId");
            if (device == null || device.isBlank()) continue;
            String capId = String.valueOf(cap.get("_id"));
            String kpiId = cap.getString("kpiId");
            String userId = cap.getString("userId");
            Integer version = cap.getInteger("version", 1);
            String os = osOf(device, userId, kpiId, version);

            if (deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(kpiId, device, version).isEmpty()) {
                boolean latest = isLatest(kpiId, userId, version);
                String status = cap.getString("status");
                boolean keep = latest && status != null && LIVE_OR_IN_FLIGHT.contains(status);
                boolean activated = Boolean.TRUE.equals(cap.getBoolean("collectorActivated"));
                String hash = cap.getString("contractHash");
                Object pulled = cap.get("lastPulledOn");
                deployments.save(KpiDeployment.builder()
                        .userId(userId).kpiId(kpiId).capabilityId(capId).capabilityVersion(version).deviceId(device).os(os)
                        .target(keep ? KpiDeploymentTarget.ACTIVE : KpiDeploymentTarget.REMOVED)
                        .status(!keep ? KpiDeploymentStatus.REMOVED : activated ? KpiDeploymentStatus.ACTIVE : KpiDeploymentStatus.PENDING)
                        .collectorId(cap.getString("collectorId"))
                        .contractHash(hash)
                        .approved(hash != null && hash.equals(cap.getString("approvedContractHash")))
                        .activated(activated)
                        .readingsSince(cap.getString("readingsSince"))
                        .lastPulledOn(toLocalDate(pulled))
                        .createdAt(Instant.now()).updatedAt(Instant.now())
                        .build());
                rows++;
            }

            Update u = new Update().set("targetDeviceIds", new ArrayList<>(List.of(device))).set("combine", "sum");
            OLD_FIELDS.forEach(u::unset);
            String bundleJson = cap.getString("collectorBundle");
            CollectorBundle bundle = CollectorBundle.fromJson(bundleJson);
            if (os != null && bundle != null && bundle.isLegacy()) {
                u.set("collectorBundle", bundle.withVariantFor(os, "sum").toJson());
                rewritten++;
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(cap.get("_id"))), u, CAPABILITIES);
        }
        mongo.save(new Document("_id", MIGRATION_ID).append("at", new Date()), MIGRATION_COLLECTION);
        log.info("[MultiDeviceMigration] {} deployments created, {} bundles rewritten to variants", rows, rewritten);
        return true;
    }

    /** The device's OS when known: an "os" field on the relay device document (not stored today), else an existing deployment's os. */
    private String osOf(String deviceId, String userId, String kpiId, Integer version) {
        Document d = mongo.findOne(Query.query(Criteria.where("_id").is(deviceId)), Document.class, DEVICES);
        if (d != null && d.getString("os") != null && !d.getString("os").isBlank()) return d.getString("os");
        return deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(kpiId, deviceId, version)
                .map(KpiDeployment::getOs).filter(o -> o != null && !o.isBlank()).orElse(null);
    }

    private boolean isLatest(String kpiId, String userId, Integer version) {
        Query q = Query.query(Criteria.where("kpiId").is(kpiId).and("userId").is(userId).and("version").gt(version));
        return !mongo.exists(q, CAPABILITIES);
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof Date d) return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (o instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
