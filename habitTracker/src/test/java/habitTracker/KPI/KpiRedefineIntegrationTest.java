package habitTracker.KPI;

import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentReconciler;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Redefine (kpi-multidevice.md, agent 10): a running KPI reopens the propose/refine loop. The live version keeps
 * running untouched until the new one goes live; going live creates a row per target device; meaningChanged is set
 * when the unit or the source types differ. LLM and runner build are mocked, like CapabilityAgentIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class KpiRedefineIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean CapabilityGenerator generator;
    @MockitoBean RunnerCollectorPipeline pipeline;
    @MockitoBean KpiDeploymentReconciler reconciler;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilities;
    @Autowired KpiDeploymentRepository deployments;
    @Autowired CapabilityAgentService service;
    @Autowired CapabilityLifecycleService lifecycle;

    UserPrincipal alice;
    KPI kpi;

    @BeforeEach
    void setUp() {
        AuthTestHelper auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        mongoTemplate.dropCollection(KpiDeployment.class);
        mongoTemplate.dropCollection(User.class);
        reset(generator, pipeline, reconciler);
        alice = auth.register("alice@example.com");
        kpi = kpiRepository.save(KPI.builder().name("Focus").userId(alice.getId()).active(true).higherIsBetter(true)
                .proxyType(ProxyType.RUNNER_COLLECTOR).proxyStatus(ProxyStatus.ACTIVE).build());
    }

    private KPIProxyCapability live(String unit, List<String> sources) {
        KPIProxyCapability c = capabilities.save(KPIProxyCapability.builder()
                .userId(alice.getId()).kpiId(kpi.getId()).platform(CapabilityPlatform.RUNNER)
                .description("hours in the editor").status(CapabilityStatus.ACTIVE).version(1)
                .declaredCapabilities(Map.of("sources", sources)).lastUnit(unit)
                .targetDeviceIds(List.of("d1", "d2")).build());
        deployments.save(KpiDeployment.builder().userId(alice.getId()).kpiId(kpi.getId()).capabilityId(c.getId())
                .capabilityVersion(1).deviceId("d1").collectorId("kpi-" + kpi.getId()).build());
        return c;
    }

    private KPIProxyCapability redefine(List<String> sources, String newUnit) {
        when(generator.propose(anyString(), anyString(), anyString(), any()))
                .thenReturn(new CapabilityGenerator.Proposal("Count it", sources));
        doAnswer(inv -> {
            KPIProxyCapability cap = inv.getArgument(0);
            return lifecycle.testGatePassed(cap, inv.getArgument(1), new CapabilityOutput(3.0, newUnit, List.of()),
                    LocalDate.now().minusDays(1), false);
        }).when(pipeline).attempt(any(), any(), anyInt());
        CapabilityDTO dto = service.redefineAs(alice.getId(), "Focus", null, null);
        return capabilities.findById(dto.id()).orElseThrow();
    }

    private KPIProxyCapability goLive(KPIProxyCapability proposed) {
        service.approveAs(alice.getId(), "Focus", proposed.getId());
        service.confirmAs(alice.getId(), "Focus", proposed.getId());
        return capabilities.findById(proposed.getId()).orElseThrow();
    }

    @Test
    void redefine_keepsLiveActive_andSeedsNewProposedFromIt() {
        KPIProxyCapability v1 = live("hours", List.of("activitywatch"));
        // a plain propose on a running KPI is still refused; only redefine may start beside the live version
        assertThrows(IllegalStateException.class, () -> service.proposeAs(alice.getId(), "Focus", "x"));
        KPIProxyCapability v2 = redefine(List.of("activitywatch"), "hours");
        assertEquals(CapabilityStatus.PROPOSED, v2.getStatus());
        assertEquals(2, v2.getVersion());
        assertEquals("hours in the editor", v2.getDescription());
        assertEquals(List.of("d1", "d2"), v2.getTargetDeviceIds());
        assertEquals(CapabilityStatus.ACTIVE, capabilities.findById(v1.getId()).orElseThrow().getStatus());
    }

    @Test
    void redefine_onAKpiThatIsNotRunning_isRefused() {
        assertThrows(IllegalStateException.class, () -> service.redefineAs(alice.getId(), "Focus", null, null));
    }

    @Test
    void goingLive_createsARowForEveryTargetDevice_andLeavesTheOldRowsAlone() {
        live("hours", List.of("activitywatch"));
        KPIProxyCapability v2 = goLive(redefine(List.of("activitywatch"), "hours"));
        assertTrue(v2.getStatus() == CapabilityStatus.SHADOW || v2.getStatus() == CapabilityStatus.ACTIVE);
        List<KpiDeployment> rows = deployments.findByKpiIdAndCapabilityVersion(kpi.getId(), 2);
        assertEquals(2, rows.size());
        assertEquals(List.of("d1", "d2"), rows.stream().map(KpiDeployment::getDeviceId).sorted().toList());
        assertTrue(rows.stream().allMatch(r -> r.getTarget() == KpiDeploymentTarget.ACTIVE
                && r.getCollectorId().equals("kpi-" + kpi.getId() + "-v2")));
        // the reconciler (not this path) retires version 1 once version 2 runs per device
        KpiDeployment old = deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(kpi.getId(), "d1", 1).orElseThrow();
        assertEquals(KpiDeploymentTarget.ACTIVE, old.getTarget());
        verify(reconciler, atLeastOnce()).kick("d2");
    }

    @Test
    void abandoningARedefine_leavesTheLiveVersionAndItsRowsAsTheyWere() {
        KPIProxyCapability v1 = live("hours", List.of("activitywatch"));
        KPIProxyCapability v2 = redefine(List.of("activitywatch"), "hours");
        service.approveAs(alice.getId(), "Focus", v2.getId()); // built, waiting for "Looks right"
        service.rejectAs(alice.getId(), "Focus", v2.getId());

        assertEquals(CapabilityStatus.REJECTED, capabilities.findById(v2.getId()).orElseThrow().getStatus());
        assertEquals(CapabilityStatus.ACTIVE, capabilities.findById(v1.getId()).orElseThrow().getStatus());
        assertEquals(ProxyStatus.ACTIVE, kpiRepository.findById(kpi.getId()).orElseThrow().getProxyStatus());
        assertEquals(1, deployments.findByKpiIdAndCapabilityVersion(kpi.getId(), 1).size());
        assertNull(capabilities.findById(v1.getId()).orElseThrow().getMeaningChanged());
    }

    @Test
    void meaningChanged_true_whenTheUnitDiffers() {
        live("hours", List.of("activitywatch"));
        KPIProxyCapability v2 = goLive(redefine(List.of("activitywatch"), "minutes"));
        assertEquals(Boolean.TRUE, v2.getMeaningChanged());
        assertEquals(LocalDate.now(), v2.getLiveSince());
    }

    @Test
    void meaningChanged_true_whenTheSourceTypesDiffer() {
        live("hours", List.of("activitywatch"));
        KPIProxyCapability v2 = goLive(redefine(List.of("file"), "hours"));
        assertEquals(Boolean.TRUE, v2.getMeaningChanged());
    }

    @Test
    void meaningChanged_false_whenUnitAndSourcesAreTheSame() {
        live("hours", List.of("activitywatch"));
        KPIProxyCapability v2 = goLive(redefine(List.of("activitywatch"), "Hours"));
        assertEquals(Boolean.FALSE, v2.getMeaningChanged());
    }
}
