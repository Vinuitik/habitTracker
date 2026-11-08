package habitTracker.KPI;

import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * One-time cleanup after the server KPI platform was removed (docs/designs/runner-only-kpis.md H1). Idempotent;
 * the `_migration` doc {_id: MIGRATION_ID} marks it done. All writes are raw (collection names + strings), never
 * through the mapped classes: an old document with platform SERVER / proxyType SANDBOX_CAPABILITY can no longer be
 * mapped (those enum values are gone) and would throw on read.
 *   kpi_proxy_capabilities platform SERVER|WINDOWS → status RETIRED, retiredPlatform = old value, platform null
 *   kpis proxyType SANDBOX_CAPABILITY → proxyType NONE (manual), proxyStatus ACTIVE, consecutiveProxyFailures 0
 *   drop kpi_capability_secrets
 * Runs in @PostConstruct, not as an ApplicationRunner: UpdateScheduler's startup pass (also @PostConstruct) reads
 * every active KPI, so it @DependsOn this bean to see migrated documents.
 */
@Component("runnerOnlyKpiMigration")
public class RunnerOnlyKpiMigration {

    private static final Logger log = LoggerFactory.getLogger(RunnerOnlyKpiMigration.class);

    static final String MIGRATION_ID = "runner-only-kpis-v1";
    static final String MIGRATION_COLLECTION = "_migration";
    static final String CAPABILITIES = "kpi_proxy_capabilities";
    static final String KPIS = "kpis";
    static final String SECRETS = "kpi_capability_secrets";
    static final List<String> REMOVED_PLATFORMS = List.of("SERVER", "WINDOWS");

    private final MongoTemplate mongo;

    public RunnerOnlyKpiMigration(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** @return true when this call did the work, false when the flag says it was already done. */
    @PostConstruct
    public boolean migrate() {
        if (mongo.exists(Query.query(Criteria.where("_id").is(MIGRATION_ID)), MIGRATION_COLLECTION)) {
            return false;
        }
        long retired = 0;
        for (String platform : REMOVED_PLATFORMS) {
            retired += mongo.updateMulti(Query.query(Criteria.where("platform").is(platform)),
                    new Update().set("status", CapabilityStatus.RETIRED.name()).set("retiredPlatform", platform)
                            .set("platform", null), // explicit null: a missing field would read back as the RUNNER default
                    CAPABILITIES).getModifiedCount();
        }
        long manual = mongo.updateMulti(Query.query(Criteria.where("proxyType").is("SANDBOX_CAPABILITY")),
                new Update().set("proxyType", ProxyType.NONE.name()).set("proxyStatus", ProxyStatus.ACTIVE.name())
                        .set("consecutiveProxyFailures", 0),
                KPIS).getModifiedCount();
        boolean hadSecrets = mongo.collectionExists(SECRETS);
        if (hadSecrets) mongo.dropCollection(SECRETS);
        mongo.save(new Document("_id", MIGRATION_ID).append("at", new Date()), MIGRATION_COLLECTION);
        log.info("[RunnerOnlyKpiMigration] {} capabilities retired, {} KPIs set to manual, {}", retired, manual,
                hadSecrets ? "kpi_capability_secrets dropped" : "no kpi_capability_secrets collection");
        return true;
    }
}
