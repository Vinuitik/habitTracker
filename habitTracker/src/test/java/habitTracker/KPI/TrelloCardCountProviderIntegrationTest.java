package habitTracker.KPI;

import com.sun.net.httpserver.HttpServer;
import habitTracker.updater.KPIProxyFillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TrelloCardCountProvider wired through the real Spring context + a full
 * KPIProxyFillService pass, against a local HttpServer standing in for Trello (the project has no
 * WireMock dependency — see pom.xml — so a bare com.sun.net.httpserver.HttpServer stub is used
 * instead, per the M2 spec's "WireMock if already a dependency, otherwise a simple local HTTP
 * stub is fine").
 *
 * Verifies two things a mocked-RestTemplate unit test can't: (1) the actual HTTP request shape
 * TrelloCardCountProvider sends — path scoped to the configured list id, key/token as query
 * params — and (2) that the value it resolves ends up persisted as a KPIData with
 * source=PROXY_TRELLO via the same nightly-job code path (KPIProxyFillService) production uses.
 */
// MOCK web environment (not NONE): habitTracker.auth.SecurityConfig's MvcRequestMatcher-based
// filter chain needs Spring MVC's HandlerMappingIntrospector bean, which only gets registered
// when a (mock) web ApplicationContext is bootstrapped.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class TrelloCardCountProviderIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    static HttpServer trelloStub;
    static volatile String lastRequestPath;
    static volatile String lastRequestQuery;
    static volatile String stubResponseBody = "[]";

    @DynamicPropertySource
    static void trelloBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        trelloStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        trelloStub.createContext("/1/lists/", exchange -> {
            lastRequestPath = exchange.getRequestURI().getPath();
            lastRequestQuery = exchange.getRequestURI().getQuery();
            byte[] body = stubResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        trelloStub.start();
        registry.add("trello.api.base-url", () -> "http://localhost:" + trelloStub.getAddress().getPort());
    }

    @Autowired MongoTemplate mongoTemplate;
    @Autowired KPIRepository kpiRepository;
    @Autowired TrelloCredentialRepository credentialRepository;
    @Autowired KPIProxyFillService kpiProxyFillService;
    @Autowired KPICollectionNameUtil collectionNameUtil;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(TrelloCredential.class);
        stubResponseBody = "[]";
        lastRequestPath = null;
        lastRequestQuery = null;
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(TrelloCredential.class);
    }

    @Test
    void proxyFillPass_hitsCorrectListWithAuthParams_andWritesKPIDataWithProxyTrelloSource() {
        String userId = "trello-user-1";
        credentialRepository.save(TrelloCredential.builder()
                .userId(userId).apiKey("app-key-1").token("user-token-1").build());

        LocalDate targetDate = LocalDate.now().minusDays(1); // KPIProxyFillService always fills "yesterday"
        stubResponseBody = """
                [
                  {"date":"%sT12:00:00.000Z","data":{"card":{"id":"card-99"},"listAfter":{"id":"list-done-1"}}}
                ]
                """.formatted(targetDate);

        KPI kpi = kpiRepository.save(KPI.builder()
                .name("Cards Closed")
                .userId(userId)
                .active(true)
                .higherIsBetter(true)
                .proxyType(ProxyType.TRELLO_CARD_COUNT)
                .proxyConfig(Map.of("boardId", "board-1", "listId", "list-done-1"))
                .build());

        kpiProxyFillService.fillFromProxies();

        // Request shape: scoped to the configured list, real key+token as query params.
        assertNotNull(lastRequestPath);
        assertEquals("/1/lists/list-done-1/actions", lastRequestPath);
        assertTrue(lastRequestQuery.contains("key=app-key-1"), lastRequestQuery);
        assertTrue(lastRequestQuery.contains("token=user-token-1"), lastRequestQuery);
        assertTrue(lastRequestQuery.contains("filter=updateCard"), lastRequestQuery);

        // Resulting KPIData: value from the stub response, tagged with source=PROXY_TRELLO.
        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());
        Optional<KPIData> saved = Optional.ofNullable(
                mongoTemplate.findOne(
                        org.springframework.data.mongodb.core.query.Query.query(
                                org.springframework.data.mongodb.core.query.Criteria.where("date").is(targetDate)),
                        KPIData.class, collectionName));

        assertTrue(saved.isPresent());
        assertEquals(1.0, saved.get().getValue());
        assertEquals(KPIDataSource.PROXY_TRELLO, saved.get().getSource());
    }

    @Test
    void proxyFillPass_noCredentials_skipsKPI_leavesNoDataWritten() {
        String userId = "trello-user-no-creds";
        // No TrelloCredential saved for this user.

        KPI kpi = kpiRepository.save(KPI.builder()
                .name("Cards Closed")
                .userId(userId)
                .active(true)
                .higherIsBetter(true)
                .proxyType(ProxyType.TRELLO_CARD_COUNT)
                .proxyConfig(Map.of("boardId", "board-1", "listId", "list-done-1"))
                .build());

        kpiProxyFillService.fillFromProxies();

        assertNull(lastRequestPath, "Trello must never be called when credentials are missing");

        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());
        long count = mongoTemplate.getCollection(collectionName).countDocuments();
        assertEquals(0, count);
    }
}
