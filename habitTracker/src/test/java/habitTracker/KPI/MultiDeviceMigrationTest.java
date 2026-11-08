package habitTracker.KPI;

import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.KpiDeploymentStatus;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** kpi-multidevice.md migration: per-device capability fields move to one KpiDeployment row; second pass is a no-op. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class MultiDeviceMigrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MongoTemplate mongoTemplate;
    @Autowired MultiDeviceMigration migration;
    @Autowired KpiDeploymentRepository deployments;
    @Autowired KPIProxyCapabilityRepository capabilities;

    private static final String LEGACY_BUNDLE = "{\"manifest\":{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"min\","
            + "\"aggregation\":\"sum\",\"sources\":[{\"type\":\"file\",\"path\":\"/x\"}]},\"files\":{\"collector.py\":\"print(1)\"}}";

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(MultiDeviceMigration.CAPABILITIES);
        mongoTemplate.dropCollection(KpiDeployment.class);
        mongoTemplate.dropCollection(MultiDeviceMigration.DEVICES);
        mongoTemplate.dropCollection(MultiDeviceMigration.MIGRATION_COLLECTION);
    }

    private Document cap(String id, String kpi, int version, String status, String device) {
        Document d = new Document("_id", id).append("kpiId", kpi).append("userId", "u").append("platform", "RUNNER")
                .append("status", status).append("version", version).append("collectorBundle", LEGACY_BUNDLE);
        if (device != null) {
            d.append("runnerDeviceId", device).append("collectorId", "kpi-" + kpi).append("readingsSince", "2026-11-04T00:20:00Z")
                    .append("lastPulledOn", java.util.Date.from(LocalDate.of(2026, 11, 5).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
                    .append("collectorActivated", true).append("contractHash", "h1").append("approvedContractHash", "h1");
        }
        return d;
    }

    @Test
    void carriesPerDeviceFieldsToADeployment_setsTargets_unsetsOldFields_andIsIdempotent() {
        mongoTemplate.insert(cap("c1", "k1", 1, "DISABLED", "dev-1"), MultiDeviceMigration.CAPABILITIES);
        mongoTemplate.insert(cap("c2", "k1", 2, "ACTIVE", "dev-1"), MultiDeviceMigration.CAPABILITIES);
        mongoTemplate.insert(cap("c3", "k2", 1, "PROPOSED", null), MultiDeviceMigration.CAPABILITIES); // no device yet

        assertTrue(migration.migrate());

        List<KpiDeployment> rows = deployments.findByKpiId("k1");
        assertEquals(2, rows.size());
        KpiDeployment live = deployments.findByKpiIdAndDeviceIdAndCapabilityVersion("k1", "dev-1", 2).orElseThrow();
        assertEquals("kpi-k1", live.getCollectorId());
        assertEquals("2026-11-04T00:20:00Z", live.getReadingsSince());
        assertEquals(LocalDate.of(2026, 11, 5), live.getLastPulledOn());
        assertTrue(live.isActivated());
        assertTrue(live.isApproved());
        assertEquals("h1", live.getContractHash());
        assertEquals("u", live.getUserId());
        assertEquals("c2", live.getCapabilityId());
        assertEquals(KpiDeploymentTarget.ACTIVE, live.getTarget());
        assertEquals(KpiDeploymentStatus.ACTIVE, live.getStatus());
        KpiDeployment old = deployments.findByKpiIdAndDeviceIdAndCapabilityVersion("k1", "dev-1", 1).orElseThrow();
        assertEquals(KpiDeploymentTarget.REMOVED, old.getTarget(), "a superseded version is never resurrected");
        assertEquals(0, deployments.findByKpiId("k2").size());

        KPIProxyCapability c2 = capabilities.findById("c2").orElseThrow();
        assertEquals(List.of("dev-1"), c2.getTargetDeviceIds());
        assertEquals("sum", c2.getCombine());
        Document raw = mongoTemplate.findById("c2", Document.class, MultiDeviceMigration.CAPABILITIES);
        for (String f : List.of("runnerDeviceId", "collectorId", "readingsSince", "lastPulledOn", "collectorActivated")) {
            assertFalse(raw.containsKey(f), f + " must be gone");
        }
        // os unknown (relay devices store none): the bundle stays legacy and keeps resolving
        assertTrue(CollectorBundle.fromJson(c2.getCollectorBundle()).isLegacy());

        assertFalse(migration.migrate(), "flag set: second call does nothing");
        mongoTemplate.dropCollection(MultiDeviceMigration.MIGRATION_COLLECTION); // flag lost: still no duplicates
        migration.migrate();
        assertEquals(2, deployments.findByKpiId("k1").size());
    }

    @Test
    void rewritesLegacyBundleToVariants_whenDeviceOsIsKnown() {
        mongoTemplate.insert(new Document("_id", "dev-1").append("userId", "u").append("os", "linux"), MultiDeviceMigration.DEVICES);
        mongoTemplate.insert(cap("c1", "k1", 1, "ACTIVE", "dev-1"), MultiDeviceMigration.CAPABILITIES);

        migration.migrate();

        CollectorBundle b = CollectorBundle.fromJson(capabilities.findById("c1").orElseThrow().getCollectorBundle());
        assertFalse(b.isLegacy());
        assertEquals(java.util.Set.of("linux"), b.variants().keySet());
        assertEquals("/x", b.resolve("linux").path("sources").get(0).path("path").asText());
        assertEquals("linux", deployments.findByKpiId("k1").get(0).getOs());
    }
}
