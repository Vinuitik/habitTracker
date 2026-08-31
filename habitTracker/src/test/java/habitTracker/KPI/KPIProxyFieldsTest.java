package habitTracker.KPI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1: verifies KPI.proxyType/proxyConfig/confirmSampleRate and KPIData.source/pending
 * (de)serialize correctly through the real Mongo converter, and — the one hard backward-compat
 * requirement from the milestone spec — a pre-existing KPIData/KPI document written before
 * these fields existed reads back with source=MANUAL / proxyType=NONE rather than null.
 *
 * The "pre-existing document" cases are simulated by saving normally, then stripping the field
 * with a raw Mongo $unset (rather than hand-building BSON), so the test exercises the actual
 * driver + MappingMongoConverter round trip instead of guessing at wire format.
 */
@DataMongoTest
@Testcontainers
class KPIProxyFieldsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final String TEST_COLLECTION = "test_kpi_data_proxy_fields";

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(KPI.class);
        if (mongoTemplate.collectionExists(TEST_COLLECTION)) {
            mongoTemplate.dropCollection(TEST_COLLECTION);
        }
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(KPI.class);
        if (mongoTemplate.collectionExists(TEST_COLLECTION)) {
            mongoTemplate.dropCollection(TEST_COLLECTION);
        }
    }

    @Test
    void kpi_proxyFields_roundTripThroughMongo() {
        KPI kpi = KPI.builder()
                .name("Cards Closed")
                .userId("user-1")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .active(true)
                .proxyType(ProxyType.TRELLO_CARD_COUNT)
                .proxyConfig(Map.of("boardId", "b1", "listId", "l1"))
                .confirmSampleRate(0.5)
                .build();

        KPI saved = mongoTemplate.save(kpi);
        KPI found = mongoTemplate.findById(saved.getId(), KPI.class);

        assertNotNull(found);
        assertEquals(ProxyType.TRELLO_CARD_COUNT, found.getProxyType());
        assertEquals("b1", found.getProxyConfig().get("boardId"));
        assertEquals(0.5, found.getConfirmSampleRate());
    }

    @Test
    void kpi_builtWithoutProxyFields_defaultsToNoneAndStandardSampleRate() {
        KPI kpi = KPI.builder().name("Plain KPI").userId("user-1").active(true).build();

        assertEquals(ProxyType.NONE, kpi.getProxyType());
        assertEquals(0.2, kpi.getConfirmSampleRate());
    }

    @Test
    void kpi_missingProxyType_deserializesAsNone() {
        // Simulate a pre-M1 KPI document that predates the proxyType field entirely.
        KPI kpi = KPI.builder().name("Legacy KPI").userId("user-1").active(true)
                .proxyType(ProxyType.TRELLO_CARD_COUNT)
                .build();
        KPI saved = mongoTemplate.save(kpi);

        mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(saved.getId())),
                new Update().unset("proxyType"),
                KPI.class);

        KPI found = mongoTemplate.findById(saved.getId(), KPI.class);

        assertNotNull(found);
        assertEquals(ProxyType.NONE, found.getProxyType());
    }

    @Test
    void kpiData_sourceAndPending_roundTripThroughMongo() {
        KPIData data = KPIData.builder()
                .date(LocalDate.of(2024, 1, 1))
                .value(3.0)
                .source(KPIDataSource.PROXY_TRELLO)
                .pending(true)
                .build();

        KPIData saved = mongoTemplate.save(data, TEST_COLLECTION);
        KPIData found = mongoTemplate.findById(saved.getId(), KPIData.class, TEST_COLLECTION);

        assertNotNull(found);
        assertEquals(KPIDataSource.PROXY_TRELLO, found.getSource());
        assertEquals(true, found.getPending());
    }

    @Test
    void kpiData_builtWithoutSource_defaultsToManual() {
        KPIData data = KPIData.builder().date(LocalDate.of(2024, 1, 1)).value(70.0).build();

        assertEquals(KPIDataSource.MANUAL, data.getSource());
        assertEquals(false, data.getPending());
    }

    @Test
    void kpiData_missingSource_deserializesAsManual() {
        // Simulate a pre-M1 KPIData document that predates the `source` field entirely.
        KPIData data = KPIData.builder()
                .date(LocalDate.of(2024, 1, 1))
                .value(70.0)
                .source(KPIDataSource.PROXY_TRELLO) // will be stripped below
                .build();
        KPIData saved = mongoTemplate.save(data, TEST_COLLECTION);

        mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(saved.getId())),
                new Update().unset("source"),
                KPIData.class, TEST_COLLECTION);

        List<KPIData> all = mongoTemplate.findAll(KPIData.class, TEST_COLLECTION);

        assertEquals(1, all.size());
        assertEquals(KPIDataSource.MANUAL, all.get(0).getSource());
        assertEquals(70.0, all.get(0).getValue());
    }
}
