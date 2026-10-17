package habitTracker.KPI;

import habitTracker.updater.KPIProxyFillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M7 end-to-end: capability stored in Mongo -> SandboxCapabilityProvider -> (mocked) sandbox
 * sidecar client -> KPIData(source=PROXY_CAPABILITY), driven through the real nightly
 * KPIProxyFillService + ProxyHealthService. The sidecar itself is covered by sandbox/test_app.py
 * and a real-docker smoke run (sandbox/real_run_check.py).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SandboxCapabilityIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean SandboxClient sandboxClient;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilityRepository;
    @Autowired KPIProxyFillService fillService;
    @Autowired KPICollectionNameUtil collectionNameUtil;

    final LocalDate target = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        reset(sandboxClient);
    }

    private KPI kpiWithCapability(String userId, String code) {
        KPI kpi = kpiRepository.save(KPI.builder().name("Sandboxed").userId(userId).active(true)
                .higherIsBetter(true).proxyType(ProxyType.SANDBOX_CAPABILITY).build());
        capabilityRepository.save(KPIProxyCapability.builder().userId(userId).kpiId(kpi.getId())
                .sourceCode(code).status(CapabilityStatus.ACTIVE).build());
        return kpi;
    }

    private KPIData dataFor(KPI kpi) {
        return mongoTemplate.findOne(Query.query(Criteria.where("date").is(target)), KPIData.class,
                collectionNameUtil.toCollectionName(kpi.getId()));
    }

    private static SandboxClient.Result ok(String stdout) {
        return new SandboxClient.Result(SandboxClient.Outcome.OK, 0, stdout, "");
    }

    @Test
    void storedScriptRuns_andProducesKpiDataWithProxyCapabilitySource() {
        KPI kpi = kpiWithCapability("u1", "print(7)");
        when(sandboxClient.run(any())).thenReturn(ok("debug line\n7.5\n"));

        fillService.fillFromProxies();

        KPIData saved = dataFor(kpi);
        assertNotNull(saved);
        assertEquals(7.5, saved.getValue());
        assertEquals(KPIDataSource.PROXY_CAPABILITY, saved.getSource());

        var captor = org.mockito.ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(captor.capture());
        assertEquals("u1", captor.getValue().userId());
        assertTrue(captor.getValue().sourceCode().contains("TARGET_DATE = \"" + target + "\""));
        assertTrue(captor.getValue().sourceCode().contains("print(7)"));

        KPIProxyCapability cap = capabilityRepository.findAll().get(0);
        assertNotNull(cap.getLastRunAt());
        assertEquals(0, cap.getConsecutiveFailures());
    }

    @Test
    void hangingScript_timesOut_withoutAffectingOtherUsersRun() {
        KPI hanging = kpiWithCapability("u-hang", "while True: pass  # HANG");
        KPI healthy = kpiWithCapability("u-ok", "print(3)");
        when(sandboxClient.run(any())).thenAnswer(inv -> {
            SandboxClient.Request r = inv.getArgument(0);
            return r.sourceCode().contains("HANG")
                    ? new SandboxClient.Result(SandboxClient.Outcome.TIMEOUT, null, "", "killed after 30s")
                    : ok("3");
        });

        fillService.fillFromProxies();

        assertNull(dataFor(hanging), "timed-out run must not write a value");
        assertNotNull(dataFor(healthy), "other user's run must still land");
        assertEquals(3.0, dataFor(healthy).getValue());
        assertEquals(1, kpiRepository.findById(hanging.getId()).orElseThrow().getConsecutiveProxyFailures());
        assertEquals(0, kpiRepository.findById(healthy.getId()).orElseThrow().getConsecutiveProxyFailures());
    }

    @Test
    void nonZeroExit_isRecordedAsFailure_andTripsCircuitBreakerAtThreshold() {
        KPI kpi = kpiWithCapability("u-fail", "raise SystemExit(2)");
        when(sandboxClient.run(any())).thenReturn(
                new SandboxClient.Result(SandboxClient.Outcome.NONZERO_EXIT, 2, "", "Traceback ..."));

        fillService.fillFromProxies();
        assertNull(dataFor(kpi));
        assertEquals(1, kpiRepository.findById(kpi.getId()).orElseThrow().getConsecutiveProxyFailures());
        KPIProxyCapability cap = capabilityRepository.findAll().get(0);
        assertTrue(cap.getLastResult().contains("NONZERO_EXIT exit=2"), cap.getLastResult());
        assertEquals(1, cap.getConsecutiveFailures());

        fillService.fillFromProxies();
        fillService.fillFromProxies(); // default threshold = 3

        KPI after = kpiRepository.findById(kpi.getId()).orElseThrow();
        assertEquals(ProxyStatus.NEEDS_REPAIR, after.getProxyStatus());

        // Breaker open: the sandbox must no longer be called for this KPI.
        fillService.fillFromProxies();
        verify(sandboxClient, times(3)).run(any());
    }

    @Test
    void sidecarDown_countsAsFailure_notSilentlyDropped() {
        KPI kpi = kpiWithCapability("u-down", "print(1)");
        when(sandboxClient.run(any())).thenReturn(
                new SandboxClient.Result(SandboxClient.Outcome.SIDECAR_ERROR, null, "", "connection refused"));

        fillService.fillFromProxies();

        assertNull(dataFor(kpi));
        assertEquals(1, kpiRepository.findById(kpi.getId()).orElseThrow().getConsecutiveProxyFailures());
    }

    @Test
    void inactiveCapability_isNeverSentToSandbox() {
        KPI kpi = kpiWithCapability("u-off", "print(1)");
        List<KPIProxyCapability> caps = new ArrayList<>(capabilityRepository.findAll());
        caps.forEach(c -> c.setStatus(CapabilityStatus.DISABLED));
        capabilityRepository.saveAll(caps);

        fillService.fillFromProxies();

        verifyNoInteractions(sandboxClient);
        assertNull(dataFor(kpi));
        assertEquals(1, kpiRepository.findById(kpi.getId()).orElseThrow().getConsecutiveProxyFailures());
    }
}
