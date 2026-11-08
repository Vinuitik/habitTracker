package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.CapabilityStatus;
import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reconciler against an in-memory deployment repo and a mocked runner client. */
class KpiDeploymentReconcilerTest {

    static final ObjectMapper J = new ObjectMapper();
    static final String BUNDLE = "{\"manifest\":{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"min\",\"aggregation\":\"sum\","
            + "\"sources\":[{\"type\":\"file\",\"path\":\"/x\"}]},\"files\":{\"collector.py\":\"print(1)\"}}";

    final Map<String, KpiDeployment> store = new LinkedHashMap<>();
    KpiDeploymentRepository repo;
    KPIProxyCapabilityRepository caps;
    KPIRepository kpis;
    RunnerCollectorClient client;
    KpiDeploymentReconciler rec;
    MutableClock clock = new MutableClock();
    List<Runnable> queued = new ArrayList<>();
    int seq;

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        repo = mock(KpiDeploymentRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            KpiDeployment d = i.getArgument(0);
            if (d.getId() == null) d.setId("d" + (++seq));
            store.put(d.getId(), d);
            return d;
        });
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        when(repo.findByKpiId(anyString())).thenAnswer(i ->
                store.values().stream().filter(d -> d.getKpiId().equals(i.getArgument(0))).toList());
        when(repo.findUnconverged()).thenAnswer(i -> store.values().stream().filter(this::unconverged).toList());
        when(repo.findUnconvergedByDeviceId(anyString())).thenAnswer(i -> store.values().stream()
                .filter(d -> d.getDeviceId().equals(i.getArgument(0))).filter(this::unconverged).toList());
        caps = mock(KPIProxyCapabilityRepository.class);
        kpis = mock(KPIRepository.class);
        KPI k = new KPI();
        k.setId("k1");
        k.setName("Screen time");
        k.setUserId("u");
        when(kpis.findById("k1")).thenReturn(Optional.of(k));
        client = mock(RunnerCollectorClient.class);
        rec = new KpiDeploymentReconciler(repo, caps, kpis, client, queued::add, clock);
    }

    boolean unconverged(KpiDeployment d) {
        if (d.getStatus() == KpiDeploymentStatus.FAILED) return false;
        return d.getTarget() == KpiDeploymentTarget.ACTIVE ? d.getStatus() != KpiDeploymentStatus.ACTIVE
                : d.getStatus() != KpiDeploymentStatus.REMOVED;
    }

    KPIProxyCapability cap(String id, int version, CapabilityStatus status) {
        KPIProxyCapability c = new KPIProxyCapability();
        c.setId(id);
        c.setKpiId("k1");
        c.setUserId("u");
        c.setVersion(version);
        c.setStatus(status);
        c.setCollectorBundle(BUNDLE);
        when(caps.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    KpiDeployment dep(String capId, int version, String device) {
        return dep(capId, version, device, "kpi-k1");
    }

    KpiDeployment dep(String capId, int version, String device, String collectorId) {
        return repo.save(KpiDeployment.builder().userId("u").kpiId("k1").capabilityId(capId).capabilityVersion(version)
                .deviceId(device).os("linux").collectorId(collectorId).build());
    }

    void runnerAccepts(String state) throws Exception {
        reset(client);
        when(client.install(any(), any(), any(), any(), any(), any())).thenReturn(J.readTree("{\"contractHash\":\"h1\",\"approved\":false}"));
        when(client.approve(any(), any(), any(), any())).thenReturn(J.readTree(state == null ? "{}" : "{\"state\":\"" + state + "\"}"));
    }

    @Test
    void offlineStaysPendingWithoutAttempts() {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        KpiDeployment d = dep("c1", 1, "dev");
        when(client.install(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "off"));
        rec.sweep();
        assertEquals(KpiDeploymentStatus.PENDING, d.getStatus());
        assertEquals(0, d.getAttempts());
        assertNull(d.getNextAttemptAt());
    }

    @Test
    void connectEventInstallsApprovesActivates_thenSecondRunIsNoop() throws Exception {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        KpiDeployment d = dep("c1", 1, "dev");
        runnerAccepts(null);
        rec.onRunnerConnected(new RunnerConnected("u", "dev"));
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(KpiDeploymentStatus.ACTIVE, d.getStatus());
        assertEquals("h1", d.getContractHash());
        assertTrue(d.isActivated());
        verify(client).approve("u", "dev", "kpi-k1", "h1");
        verify(client).activate("u", "dev", "kpi-k1");
        rec.sweep();
        verify(client, times(1)).install(any(), any(), any(), any(), any(), any());
        verify(client, times(1)).activate(any(), any(), any());
    }

    @Test
    void backfillsOsAndSkipsUnapprovedCapability() throws Exception {
        KPIProxyCapability c = cap("c1", 1, CapabilityStatus.AWAITING_CONTRACT);
        KpiDeployment d = dep("c1", 1, "dev");
        d.setOs(null);
        rec.sweep();
        verifyNoInteractions(client); // user has not approved the contract yet
        c.setApprovedContractHash("h1");
        runnerAccepts(null);
        when(client.info("u", "dev")).thenReturn(new RunnerCollectorClient.RunnerInfo("windows", "amd64", List.of()));
        rec.sweep();
        assertEquals("windows", d.getOs());
        assertEquals(KpiDeploymentStatus.ACTIVE, d.getStatus());
    }

    @Test
    void errorsBackOffThenFailAfterFive_andRetryResets() throws Exception {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        KpiDeployment d = dep("c1", 1, "dev");
        when(client.install(any(), any(), any(), any(), any(), any())).thenThrow(new RunnerCallException(400, "{\"error\":\"bad manifest\"}"));
        rec.sweep();
        assertEquals(1, d.getAttempts());
        assertEquals(clock.now.plus(Duration.ofMinutes(1)), d.getNextAttemptAt());
        assertTrue(d.getLastError().contains("bad manifest"));
        rec.sweep(); // still inside the backoff window
        assertEquals(1, d.getAttempts());
        Duration[] expected = {Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        for (int i = 0; i < 3; i++) {
            clock.now = d.getNextAttemptAt();
            rec.sweep();
            assertEquals(i + 2, d.getAttempts());
            assertEquals(clock.now.plus(expected[i]), d.getNextAttemptAt());
        }
        clock.now = d.getNextAttemptAt();
        rec.sweep();
        assertEquals(KpiDeploymentStatus.FAILED, d.getStatus());
        assertEquals(5, d.getAttempts());
        rec.sweep();
        verify(client, times(5)).install(any(), any(), any(), any(), any(), any()); // parked

        // retry (as the controller does), runner now healthy
        d.setAttempts(0);
        d.setStatus(KpiDeploymentStatus.PENDING);
        d.setNextAttemptAt(clock.now);
        runnerAccepts(null);
        rec.sweep();
        assertEquals(KpiDeploymentStatus.ACTIVE, d.getStatus());
    }

    @Test
    void removedTargetDeletesCollector() {
        cap("c1", 1, CapabilityStatus.DISABLED);
        KpiDeployment d = dep("c1", 1, "dev");
        d.setStatus(KpiDeploymentStatus.ACTIVE);
        d.setTarget(KpiDeploymentTarget.REMOVED);
        rec.sweep();
        verify(client).delete("u", "dev", "kpi-k1");
        assertEquals(KpiDeploymentStatus.REMOVED, d.getStatus());
    }

    @Test
    void redefine_oldKeptUntilNewActive_thenOldMarkedRemovedWithoutDeletingSharedCollector() throws Exception {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        cap("c2", 2, CapabilityStatus.ACTIVE);
        KpiDeployment oldD = dep("c1", 1, "dev");
        oldD.setStatus(KpiDeploymentStatus.ACTIVE);
        KpiDeployment newD = dep("c2", 2, "dev");

        when(client.install(any(), any(), any(), any(), any(), any())).thenThrow(new RunnerCallException(400, "{\"error\":\"nope\"}"));
        rec.sweep();
        assertEquals(KpiDeploymentTarget.ACTIVE, oldD.getTarget()); // new version not running: old stays wanted

        clock.now = newD.getNextAttemptAt();
        runnerAccepts(null);
        rec.sweep();
        assertEquals(KpiDeploymentStatus.ACTIVE, newD.getStatus());
        assertEquals(KpiDeploymentTarget.REMOVED, oldD.getTarget());
        rec.sweep();
        assertEquals(KpiDeploymentStatus.REMOVED, oldD.getStatus());
        verify(client, never()).delete(any(), any(), any()); // same collector id is now the new version's
    }

    @Test
    void redefine_perVersionIds_oldCollectorDeletedOnlyAfterNewActive() throws Exception {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        cap("c2", 2, CapabilityStatus.ACTIVE);
        KpiDeployment oldD = dep("c1", 1, "dev", "kpi-k1-v1");
        oldD.setStatus(KpiDeploymentStatus.ACTIVE);
        KpiDeployment newD = dep("c2", 2, "dev", "kpi-k1-v2");

        when(client.install(any(), any(), any(), any(), any(), any())).thenThrow(new RunnerCallException(400, "{\"error\":\"nope\"}"));
        rec.sweep();
        verify(client, never()).delete(any(), any(), any()); // new not running yet: old keeps collecting

        clock.now = newD.getNextAttemptAt();
        runnerAccepts(null);
        rec.sweep();
        assertEquals(KpiDeploymentStatus.ACTIVE, newD.getStatus());
        verify(client, never()).delete(any(), any(), any());
        rec.sweep();
        assertEquals(KpiDeploymentStatus.REMOVED, oldD.getStatus());
        verify(client).delete("u", "dev", "kpi-k1-v1");
        verify(client, never()).delete("u", "dev", "kpi-k1-v2");
    }

    @Test
    void awaitingConfirmThenReady() throws Exception {
        cap("c1", 1, CapabilityStatus.ACTIVE);
        KpiDeployment d = dep("c1", 1, "dev");
        runnerAccepts("awaiting_confirm");
        rec.sweep();
        assertEquals(KpiDeploymentStatus.AWAITING_CONFIRM, d.getStatus());
        verify(client, never()).activate(any(), any(), any());

        when(client.list("u", "dev")).thenReturn(J.readTree("[{\"id\":\"kpi-k1\",\"requiresState\":\"installing\"}]"));
        rec.sweep();
        assertEquals(KpiDeploymentStatus.INSTALLING, d.getStatus());

        when(client.list("u", "dev")).thenReturn(J.readTree("[{\"id\":\"kpi-k1\",\"requiresState\":\"ready\"}]"));
        rec.sweep();
        assertEquals(KpiDeploymentStatus.ACTIVE, d.getStatus());
        verify(client, times(1)).install(any(), any(), any(), any(), any(), any());
        verify(client).activate("u", "dev", "kpi-k1");
    }

    @Test
    void controllerChecksOwner() {
        DeviceService devices = mock(DeviceService.class);
        UplinkRegistry uplinks = mock(UplinkRegistry.class);
        when(uplinks.isOnline("dev")).thenReturn(true);
        Device device = Device.builder().id("dev").userId("u").name("Laptop").build();
        when(devices.owned("u", "dev")).thenReturn(Optional.of(device));
        KpiDeployment d = dep("c1", 1, "dev");
        d.setUserId("u");
        d.setStatus(KpiDeploymentStatus.FAILED);
        d.setAttempts(5);
        KpiDeploymentController c = new KpiDeploymentController(repo, kpis, devices, uplinks, rec);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn("u");
            var rows = c.list("k1").getBody();
            assertEquals(1, rows.size());
            assertEquals("Laptop", rows.get(0).deviceName());
            assertTrue(rows.get(0).online());
            ResponseEntity<?> r = c.retry("k1", d.getId());
            assertEquals(200, r.getStatusCode().value());
            assertEquals(KpiDeploymentStatus.PENDING, d.getStatus());
            assertEquals(0, d.getAttempts());
            assertEquals(1, queued.size()); // reconciler kicked

            sec.when(SecurityUtils::getCurrentUserId).thenReturn("intruder");
            assertEquals(404, c.list("k1").getStatusCode().value());
            assertEquals(404, c.retry("k1", d.getId()).getStatusCode().value());
        }
    }
}
