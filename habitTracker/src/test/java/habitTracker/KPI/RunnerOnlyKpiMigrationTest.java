package habitTracker.KPI;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * runner-only-kpis.md H1 migration: raw legacy documents (enum values that no longer exist) are rewritten so the
 * mapped repositories can read them, the secrets collection is dropped, and a second pass is a no-op.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class RunnerOnlyKpiMigrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MongoTemplate mongoTemplate;
    @Autowired RunnerOnlyKpiMigration migration;
    @Autowired KPIProxyCapabilityRepository capabilities;
    @Autowired KPIRepository kpis;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(RunnerOnlyKpiMigration.CAPABILITIES);
        mongoTemplate.dropCollection(RunnerOnlyKpiMigration.KPIS);
        mongoTemplate.dropCollection(RunnerOnlyKpiMigration.SECRETS);
        mongoTemplate.dropCollection(RunnerOnlyKpiMigration.MIGRATION_COLLECTION);
    }

    @Test
    void retiresServerCapabilities_setsSandboxKpisManual_dropsSecrets_once() {
        mongoTemplate.insert(new Document("_id", "k-sbx").append("name", "PRs").append("userId", "u")
                .append("proxyType", "SANDBOX_CAPABILITY").append("proxyStatus", "NEEDS_REPAIR")
                .append("consecutiveProxyFailures", 3), RunnerOnlyKpiMigration.KPIS);
        mongoTemplate.insert(new Document("_id", "k-run").append("name", "YT").append("userId", "u")
                .append("proxyType", "RUNNER_COLLECTOR"), RunnerOnlyKpiMigration.KPIS);
        mongoTemplate.insert(new Document("_id", "c-srv").append("kpiId", "k-sbx").append("userId", "u")
                .append("platform", "SERVER").append("status", "ACTIVE").append("version", 1), RunnerOnlyKpiMigration.CAPABILITIES);
        mongoTemplate.insert(new Document("_id", "c-win").append("kpiId", "k-sbx").append("userId", "u")
                .append("platform", "WINDOWS").append("status", "PROPOSED").append("version", 2), RunnerOnlyKpiMigration.CAPABILITIES);
        mongoTemplate.insert(new Document("_id", "c-run").append("kpiId", "k-run").append("userId", "u")
                .append("platform", "RUNNER").append("status", "ACTIVE").append("version", 1), RunnerOnlyKpiMigration.CAPABILITIES);
        mongoTemplate.insert(new Document("userId", "u").append("name", "GITHUB_TOKEN"), RunnerOnlyKpiMigration.SECRETS);

        assertTrue(migration.migrate());

        KPIProxyCapability srv = capabilities.findById("c-srv").orElseThrow(); // maps again: no removed enum value left
        assertEquals(CapabilityStatus.RETIRED, srv.getStatus());
        assertNull(srv.getPlatform());
        assertEquals("SERVER", mongoTemplate.findById("c-srv", Document.class, RunnerOnlyKpiMigration.CAPABILITIES).getString("retiredPlatform"));
        assertEquals(CapabilityStatus.RETIRED, capabilities.findById("c-win").orElseThrow().getStatus());
        KPIProxyCapability run = capabilities.findById("c-run").orElseThrow();
        assertEquals(CapabilityStatus.ACTIVE, run.getStatus());
        assertEquals(CapabilityPlatform.RUNNER, run.getPlatform());

        KPI manual = kpis.findById("k-sbx").orElseThrow();
        assertEquals(ProxyType.NONE, manual.getProxyType());
        assertEquals(ProxyStatus.ACTIVE, manual.getProxyStatus());
        assertEquals(0, manual.getConsecutiveProxyFailures());
        assertEquals(ProxyType.RUNNER_COLLECTOR, kpis.findById("k-run").orElseThrow().getProxyType());
        assertFalse(mongoTemplate.collectionExists(RunnerOnlyKpiMigration.SECRETS));

        assertFalse(migration.migrate(), "second pass is a no-op");
    }
}
