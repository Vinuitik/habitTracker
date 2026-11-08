package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.deploy.DeviceCombiner;
import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import habitTracker.KPI.deploy.KpiDeploymentStatus;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.KPI.deploy.KpiDeviceReading;
import habitTracker.KPI.deploy.KpiDeviceReadingRepository;
import habitTracker.KPI.DeviceReadingController.DeviceReadingDTO;
import habitTracker.auth.UserPrincipal;
import habitTracker.relay.DeviceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Per-device readings, combine rules, partial/backfill, MANUAL safety, owner check (no Spring/Mongo). */
class DeviceAggregationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    KpiDeviceReadingRepository readingRepo = mock(KpiDeviceReadingRepository.class);
    DynamicKPIDataRepository data = mock(DynamicKPIDataRepository.class);
    KpiDeploymentRepository deployments = mock(KpiDeploymentRepository.class);
    ProxyReadingRecorder recorder = mock(ProxyReadingRecorder.class);
    ProxyHealthService health = mock(ProxyHealthService.class);
    RunnerCollectorProvider provider;

    final List<KpiDeviceReading> store = new ArrayList<>();
    final Map<LocalDate, KPIData> points = new HashMap<>();
    KPI kpi;
    KPIProxyCapability cap;

    @BeforeEach
    void setUp() {
        provider = new RunnerCollectorProvider(mock(KPIRepository.class), mock(KPIProxyCapabilityRepository.class), deployments,
                null, health, recorder, readingRepo, data, new KPICollectionNameUtil());
        kpi = KPI.builder().id("k1").userId("u1").name("Screen").build();
        cap = KPIProxyCapability.builder().kpiId("k1").userId("u1").version(1).status(CapabilityStatus.ACTIVE).build();

        when(readingRepo.findByKpiIdAndDeviceIdAndDate(anyString(), anyString(), any())).thenAnswer(i ->
                store.stream().filter(r -> r.getDeviceId().equals(i.getArgument(1)) && r.getDate().equals(i.getArgument(2))).findFirst());
        when(readingRepo.findByKpiIdAndDate(anyString(), any())).thenAnswer(i ->
                store.stream().filter(r -> r.getDate().equals(i.getArgument(1))).toList());
        when(readingRepo.save(any())).thenAnswer(i -> {
            KpiDeviceReading r = i.getArgument(0);
            if (!store.contains(r)) store.add(r);
            return r;
        });
        when(data.findByDate(any(), anyString())).thenAnswer(i -> Optional.ofNullable(points.get(i.<LocalDate>getArgument(0))));
        when(data.save(any(), anyString())).thenAnswer(i -> {
            KPIData d = i.getArgument(0);
            points.put(d.getDate(), d);
            return d;
        });
        // the real recorder would write the point; emulate just that
        doAnswer(i -> {
            LocalDate day = i.getArgument(1);
            KPIData d = points.getOrDefault(day, KPIData.builder().date(day).build());
            d.setValue(i.getArgument(2));
            d.setSource(KPIDataSource.PROXY_CAPABILITY);
            points.put(day, d);
            return null;
        }).when(recorder).record(any(), any(), anyDouble(), any(), any());
    }

    void devices(String combine, String... ids) {
        cap.setCombine(combine);
        List<KpiDeployment> rows = new ArrayList<>();
        for (String id : ids) {
            rows.add(KpiDeployment.builder().kpiId("k1").deviceId(id).collectorId("kpi-k1").capabilityVersion(1)
                    .target(KpiDeploymentTarget.ACTIVE).build());
        }
        when(deployments.findByKpiId("k1")).thenReturn(rows);
    }

    int push(String device, double v, String recordedAt) throws Exception {
        JsonNode r = JSON.readTree("{\"value\":" + v + ",\"unit\":\"hours\",\"recordedAt\":\"" + recordedAt + "\"}");
        return provider.storeDeviceReadings(kpi, cap, KpiDeployment.builder().deviceId(device).build(), Map.of(DAY, r));
    }

    @Test
    void combineRules() {
        List<Double> v = List.of(2.0, 4.0, 9.0);
        assertEquals(15.0, DeviceCombiner.combine("sum", v));
        assertEquals(9.0, DeviceCombiner.combine("max", v));
        assertEquals(5.0, DeviceCombiner.combine("avg", v));
        assertEquals(9.0, DeviceCombiner.combine("latest", v));
        assertNull(DeviceCombiner.combine("sum", List.of()));
    }

    @Test
    void latestUsesRecordedAtNotArrivalOrder() throws Exception {
        devices("latest", "a", "b");
        push("b", 7, "2026-11-08T01:00:00Z"); // newest value arrives first
        push("a", 3, "2026-11-08T00:00:00Z"); // older reading arrives later
        assertEquals(7.0, points.get(DAY).getValue());
    }

    @Test
    void partialDayIsFlaggedAndBackfillCompletesIt() throws Exception {
        devices("sum", "a", "b", "c");
        push("a", 1, "2026-11-08T00:00:00Z");
        push("b", 2, "2026-11-08T00:00:01Z");
        assertEquals(3.0, points.get(DAY).getValue());
        assertEquals(2, points.get(DAY).getDevicesReported());
        assertEquals(3, points.get(DAY).getDevicesExpected());

        push("c", 4, "2026-11-09T00:00:00Z"); // late backfill from the third device
        assertEquals(7.0, points.get(DAY).getValue());
        assertEquals(3, points.get(DAY).getDevicesReported());
        assertEquals(3, points.get(DAY).getDevicesExpected());
    }

    @Test
    void newerReadingFromSameDeviceWinsStaleIgnored() throws Exception {
        devices("sum", "a", "b");
        push("a", 1, "2026-11-08T10:00:00Z");
        push("a", 5, "2026-11-08T11:00:00Z");
        push("a", 99, "2026-11-08T09:00:00Z"); // stale re-delivery
        assertEquals(1, store.size());
        assertEquals(5.0, points.get(DAY).getValue());
    }

    @Test
    void singleDevicePathLeavesDeviceCountsNull() throws Exception {
        devices("sum", "a");
        assertEquals(1, push("a", 2.5, "2026-11-08T00:00:00Z"));
        assertEquals(2.5, points.get(DAY).getValue());
        assertNull(points.get(DAY).getDevicesReported());
        assertNull(points.get(DAY).getDevicesExpected());
        verify(data, never()).save(any(), anyString());
    }

    @Test
    void manualPointIsNeverOverwritten() throws Exception {
        devices("sum", "a", "b");
        points.put(DAY, KPIData.builder().date(DAY).value(42.0).source(KPIDataSource.MANUAL).build());
        assertEquals(0, push("a", 1, "2026-11-08T00:00:00Z"));
        assertEquals(42.0, points.get(DAY).getValue());
        verify(recorder, never()).record(any(), any(), anyDouble(), any(), any());
    }

    @Test
    void duringRollout_deviceOnV2AndDeviceStillOnV1BothCombined() throws Exception {
        cap.setVersion(2);
        cap.setCombine("sum");
        KpiDeployment aV1 = row("a", 1, "kpi-k1-v1", KpiDeploymentTarget.ACTIVE, KpiDeploymentStatus.ACTIVE);
        KpiDeployment aV2 = row("a", 2, "kpi-k1-v2", KpiDeploymentTarget.ACTIVE, KpiDeploymentStatus.ACTIVE);
        KpiDeployment bV1 = row("b", 1, "kpi-k1-v1", KpiDeploymentTarget.ACTIVE, KpiDeploymentStatus.ACTIVE);
        KpiDeployment bV2 = row("b", 2, "kpi-k1-v2", KpiDeploymentTarget.ACTIVE, KpiDeploymentStatus.PENDING); // not installed yet
        when(deployments.findByKpiId("k1")).thenReturn(List.of(aV1, aV2, bV1, bV2));
        // pull reads a@v2 and b@v1 only
        java.util.List<String> ids = new ArrayList<>();
        RunnerCollectorClient client = mock(RunnerCollectorClient.class);
        provider = new RunnerCollectorProvider(mock(KPIRepository.class), repo(cap), deployments, client, health, recorder,
                readingRepo, data, new KPICollectionNameUtil());
        when(client.list(any(), any())).thenReturn(JSON.createArrayNode());
        when(client.readings(any(), any(), any(), any())).thenAnswer(i -> {
            ids.add(i.getArgument(1) + ":" + i.getArgument(2));
            return JSON.readTree("[{\"from\":\"2026-11-07T00:00:00Z\",\"value\":3,\"unit\":\"hours\",\"recordedAt\":\"2026-11-08T01:00:00Z\"}]");
        });
        provider.pull(kpi);
        assertEquals(List.of("a:kpi-k1-v2", "b:kpi-k1-v1"), ids);
        assertEquals(6.0, points.get(DAY).getValue()); // both devices, expected = 2
        assertEquals(2, points.get(DAY).getDevicesExpected());
    }

    private KpiDeployment row(String device, int version, String collectorId, KpiDeploymentTarget t, KpiDeploymentStatus s) {
        return KpiDeployment.builder().kpiId("k1").deviceId(device).capabilityVersion(version).collectorId(collectorId)
                .target(t).status(s).activated(s == KpiDeploymentStatus.ACTIVE).build();
    }

    private KPIProxyCapabilityRepository repo(KPIProxyCapability c) {
        KPIProxyCapabilityRepository r = mock(KPIProxyCapabilityRepository.class);
        when(r.findFirstByKpiIdAndUserIdOrderByVersionDesc("k1", "u1")).thenReturn(Optional.of(c));
        return r;
    }

    @Test
    void readingsFromNonActiveDevicesAreIgnoredInTheTotal() throws Exception {
        devices("sum", "a", "b");
        push("old-device", 100, "2026-11-08T00:00:00Z"); // not an ACTIVE-target device
        assertNull(points.get(DAY));
        push("a", 1, "2026-11-08T00:00:00Z");
        assertEquals(1.0, points.get(DAY).getValue());
    }

    @Test
    void deviceReadingsEndpointIsOwnerChecked() {
        KPIRepository kpis = mock(KPIRepository.class);
        DeviceRepository devs = mock(DeviceRepository.class);
        when(kpis.findById("k1")).thenReturn(Optional.of(kpi));
        KpiDeviceReading row = KpiDeviceReading.builder().kpiId("k1").deviceId("d1").date(DAY).value(2).unit("hours").build();
        when(readingRepo.findByKpiIdAndDateBetweenOrderByDateAscDeviceIdAsc(eq("k1"), any(), any())).thenReturn(List.of(row));
        DeviceReadingController c = new DeviceReadingController(kpis, readingRepo, devs);

        as("intruder");
        assertEquals(404, c.deviceReadings("k1", null, null).getStatusCode().value());
        as("u1");
        ResponseEntity<?> ok = c.deviceReadings("k1", DAY, DAY);
        @SuppressWarnings("unchecked") List<DeviceReadingDTO> body = (List<DeviceReadingDTO>) ok.getBody();
        assertEquals(1, body.size());
        assertEquals("d1", body.get(0).deviceName()); // unknown device falls back to id
        assertEquals(2.0, body.get(0).value());
    }

    static void as(String userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getId()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }
}
