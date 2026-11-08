package habitTracker.KPI.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.CapabilityStatus;
import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.ProxyType;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Retire → rows REMOVED, uninstall mapping (409/400), queued uninstall on RunnerConnected, owner checks. */
class KpiRetireAndUninstallTest {

    static final ObjectMapper J = new ObjectMapper();
    static final String BUNDLE = "{\"manifest\":{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"h\",\"aggregation\":\"sum\","
            + "\"combine\":\"sum\",\"variants\":{\"linux\":{\"requires\":[{\"manager\":\"relay\",\"id\":\"activitywatch\",\"why\":\"tracks time\"}],"
            + "\"sources\":[{\"type\":\"activitywatch\"}]}}},\"files\":{\"collector.py\":\"print(1)\"}}";

    final Map<String, KpiDeployment> rows = new LinkedHashMap<>();
    final Map<String, PendingPackageUninstall> queue = new LinkedHashMap<>();
    KpiDeploymentRepository depRepo;
    PendingPackageUninstallRepository pendRepo;
    KPIProxyCapabilityRepository capRepo;
    KPIRepository kpiRepo;
    DeviceService devices;
    UplinkRegistry uplinks;
    RunnerCollectorClient client;
    KpiDeploymentReconciler reconciler;
    KPI kpi;
    KPIProxyCapability cap;
    PackageUninstallService svc;
    KpiRetireService retire;
    int seq;

    @BeforeEach
    void setUp() {
        depRepo = mock(KpiDeploymentRepository.class);
        when(depRepo.save(any())).thenAnswer(i -> {
            KpiDeployment d = i.getArgument(0);
            if (d.getId() == null) d.setId("d" + (++seq));
            rows.put(d.getId(), d);
            return d;
        });
        when(depRepo.findByKpiId(anyString())).thenAnswer(i ->
                rows.values().stream().filter(d -> d.getKpiId().equals(i.getArgument(0))).toList());
        pendRepo = mock(PendingPackageUninstallRepository.class);
        when(pendRepo.save(any())).thenAnswer(i -> {
            PendingPackageUninstall p = i.getArgument(0);
            if (p.getId() == null) p.setId("p" + (++seq));
            queue.put(p.getId(), p);
            return p;
        });
        when(pendRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(queue.get(i.<String>getArgument(0))));
        when(pendRepo.findAll()).thenAnswer(i -> new ArrayList<>(queue.values()));
        when(pendRepo.findByDeviceId(anyString())).thenAnswer(i ->
                queue.values().stream().filter(p -> p.getDeviceId().equals(i.getArgument(0))).toList());
        when(pendRepo.findByUserIdAndDeviceIdAndManagerAndPackageId(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(i -> queue.values().stream().filter(p -> p.getUserId().equals(i.getArgument(0))
                        && p.getDeviceId().equals(i.getArgument(1)) && p.getManager().equals(i.getArgument(2))
                        && p.getPackageId().equals(i.getArgument(3))).findFirst());
        doAnswer(i -> { queue.remove(((PendingPackageUninstall) i.getArgument(0)).getId()); return null; })
                .when(pendRepo).delete(any());

        capRepo = mock(KPIProxyCapabilityRepository.class);
        kpiRepo = mock(KPIRepository.class);
        kpi = new KPI();
        kpi.setId("k1");
        kpi.setName("Coding hours");
        kpi.setUserId("u");
        kpi.setProxyType(ProxyType.RUNNER_COLLECTOR);
        when(kpiRepo.findById("k1")).thenReturn(Optional.of(kpi));
        cap = new KPIProxyCapability();
        cap.setId("c1");
        cap.setKpiId("k1");
        cap.setUserId("u");
        cap.setStatus(CapabilityStatus.ACTIVE);
        cap.setCollectorBundle(BUNDLE);
        when(capRepo.findByKpiIdAndUserId("k1", "u")).thenReturn(List.of(cap));

        devices = mock(DeviceService.class);
        Device desk = new Device();
        desk.setName("Desktop");
        when(devices.owned("u", "dev1")).thenReturn(Optional.of(desk));
        Device lap = new Device();
        lap.setName("Laptop");
        when(devices.owned("u", "dev2")).thenReturn(Optional.of(lap));
        when(devices.owned(eq("other"), anyString())).thenReturn(Optional.empty());
        when(devices.owned(eq("u"), eq("foreign"))).thenReturn(Optional.empty());
        uplinks = mock(UplinkRegistry.class);
        client = mock(RunnerCollectorClient.class);
        reconciler = mock(KpiDeploymentReconciler.class);
        svc = new PackageUninstallService(client, pendRepo, depRepo, kpiRepo, devices, uplinks, Runnable::run);
        retire = new KpiRetireService(kpiRepo, capRepo, depRepo, devices, reconciler);
    }

    KpiDeployment row(String device, KpiDeploymentStatus status) {
        KpiDeployment d = KpiDeployment.builder().userId("u").kpiId("k1").capabilityId("c1").capabilityVersion(1)
                .deviceId(device).os("linux").status(status).collectorId("kpi-k1-v1").build();
        return depRepo.save(d);
    }

    @Test
    void retireMarksCapabilityRetiredRowsRemovedAndListsRequiredPackages() {
        row("dev1", KpiDeploymentStatus.ACTIVE);
        row("dev2", KpiDeploymentStatus.ACTIVE);

        KpiRetireService.Retired r = retire.retire("u", "k1").orElseThrow();

        assertEquals(CapabilityStatus.RETIRED, cap.getStatus());
        assertTrue(rows.values().stream().allMatch(d -> d.getTarget() == KpiDeploymentTarget.REMOVED
                && d.getStatus() == KpiDeploymentStatus.REMOVING));
        assertEquals(ProxyType.NONE, kpi.getProxyType());
        verify(reconciler).kick("dev1");
        verify(reconciler).kick("dev2");
        assertEquals(1, r.packages().size());
        assertEquals("ActivityWatch", r.packages().get(0).name());
        assertEquals(List.of("Desktop", "Laptop"), r.packages().get(0).devices().stream().map(KpiRetireService.DeviceRef::deviceName).toList());
    }

    @Test
    void retireTwiceReportsNoPackagesAndDoesNotKickAgain() {
        row("dev1", KpiDeploymentStatus.ACTIVE);
        retire.retire("u", "k1");
        rows.values().forEach(d -> d.setStatus(KpiDeploymentStatus.REMOVED));
        reset(reconciler);

        KpiRetireService.Retired again = retire.retire("u", "k1").orElseThrow();

        assertTrue(again.packages().isEmpty());
        verifyNoInteractions(reconciler);
    }

    @Test
    void retireOfSomeoneElsesKpiIsRefused() {
        row("dev1", KpiDeploymentStatus.ACTIVE);
        assertTrue(retire.retire("other", "k1").isEmpty());
        assertEquals(CapabilityStatus.ACTIVE, cap.getStatus());
        assertEquals(KpiDeploymentTarget.ACTIVE, rows.values().iterator().next().getTarget());
    }

    @Test
    void uninstallSentOnceCollectorIsGone() {
        row("dev1", KpiDeploymentStatus.REMOVED);
        List<PackageUninstallService.Result> out = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("dev1"))));
        assertEquals(PackageUninstallService.Status.UNINSTALLING, out.get(0).status());
        verify(client).uninstallPackage("u", "dev1", "relay", "activitywatch");
        assertTrue(queue.isEmpty());
    }

    @Test
    void uninstallWaitsWhileCollectorIsStillBeingRemoved() {
        row("dev1", KpiDeploymentStatus.REMOVING);
        List<PackageUninstallService.Result> out = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("dev1"))));
        assertEquals(PackageUninstallService.Status.QUEUED, out.get(0).status());
        verify(client, never()).uninstallPackage(any(), any(), any(), any());
        assertEquals(1, queue.size());
    }

    @Test
    void runner409BecomesStillNeededByKpiName() throws Exception {
        row("dev1", KpiDeploymentStatus.REMOVED);
        KPI other = new KPI();
        other.setId("k2");
        other.setName("Reading");
        other.setUserId("u");
        when(kpiRepo.findById("k2")).thenReturn(Optional.of(other));
        doThrow(new RunnerCallException(409, "{\"error\":\"in use\"}")).when(client).uninstallPackage(any(), any(), any(), any());
        when(client.packages("u", "dev1")).thenReturn(J.readTree(
                "[{\"manager\":\"relay\",\"id\":\"activitywatch\",\"installedByRelay\":true,\"usedBy\":[\"kpi-k2-v1\"],\"state\":\"installed\"}]"));

        PackageUninstallService.Result r = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("dev1")))).get(0);

        assertEquals(PackageUninstallService.Status.STILL_NEEDED, r.status());
        assertEquals("Still needed by Reading.", r.message());
        assertTrue(queue.isEmpty());
    }

    @Test
    void runner400BecomesWasAlreadyThere() {
        row("dev1", KpiDeploymentStatus.REMOVED);
        doThrow(new RunnerCallException(400, "{}")).when(client).uninstallPackage(any(), any(), any(), any());
        PackageUninstallService.Result r = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("dev1")))).get(0);
        assertEquals(PackageUninstallService.Status.NOT_BY_RELAY, r.status());
        assertEquals("Was already on this computer, so Relay won't remove it.", r.message());
    }

    @Test
    void offlineDeviceIsQueuedAndSentOnRunnerConnected() {
        row("dev1", KpiDeploymentStatus.REMOVED);
        doThrow(new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "offline"))
                .when(client).uninstallPackage(any(), any(), any(), any());
        PackageUninstallService.Result r = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("dev1")))).get(0);
        assertEquals(PackageUninstallService.Status.QUEUED, r.status());
        assertEquals(1, queue.size());

        doNothing().when(client).uninstallPackage(any(), any(), any(), any());
        svc.onRunnerConnected(new RunnerConnected("u", "dev1"));

        verify(client, times(2)).uninstallPackage("u", "dev1", "relay", "activitywatch");
        assertTrue(queue.isEmpty());
    }

    @Test
    void uninstallOnAnotherUsersDeviceIsSkipped() {
        List<PackageUninstallService.Result> out = svc.request("u", "k1",
                List.of(new PackageUninstallService.Target("relay", "activitywatch", List.of("foreign"))));
        assertTrue(out.isEmpty());
        verifyNoInteractions(client);
        assertTrue(queue.isEmpty());
        assertTrue(svc.uninstallNow("other", "dev1", "relay", "activitywatch").isEmpty());
    }

    @Test
    void listExplainsWhyAPackageCannotBeUninstalled() throws Exception {
        KPI other = new KPI();
        other.setId("k1");
        when(client.packages("u", "dev1")).thenReturn(J.readTree("["
                + "{\"manager\":\"relay\",\"id\":\"activitywatch\",\"installedByRelay\":true,\"usedBy\":[\"kpi-k1-v2\"],\"state\":\"installed\"},"
                + "{\"manager\":\"winget\",\"id\":\"Foo.Bar\",\"installedByRelay\":false,\"usedBy\":[],\"state\":\"installed\"},"
                + "{\"manager\":\"brew\",\"id\":\"jq\",\"installedByRelay\":true,\"usedBy\":[],\"state\":\"installed\"}]"));

        List<PackageUninstallService.PackageRow> rowsOut = svc.list("u", "dev1");

        assertFalse(rowsOut.get(0).canUninstall());
        assertEquals(List.of("Coding hours"), rowsOut.get(0).usedByKpis());
        assertEquals("Still needed by Coding hours.", rowsOut.get(0).reason());
        assertFalse(rowsOut.get(1).canUninstall());
        assertEquals(PackageUninstallService.NOT_BY_RELAY, rowsOut.get(1).reason());
        assertTrue(rowsOut.get(2).canUninstall());
    }

    @Test
    void controllerChecksOwnerForRetireAndPackages() {
        KpiPackageController c = new KpiPackageController(retire, svc, devices, kpiRepo);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn("other");
            assertEquals(404, c.retire("k1").getStatusCode().value());
            assertEquals(404, c.list("dev1").getStatusCode().value());
            assertEquals(404, c.uninstallAfterRetire("k1", new KpiPackageController.UninstallBody(List.of())).getStatusCode().value());
            sec.when(SecurityUtils::getCurrentUserId).thenReturn("u");
            assertEquals(400, c.uninstallOne("dev1", new KpiPackageController.OneUninstallBody("relay", "--bad flag")).getStatusCode().value());
        }
    }

    @AfterEach
    void done() {
        rows.clear();
        queue.clear();
    }
}
