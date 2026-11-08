package habitTracker.KPI.deploy;

import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIProxyCapability;
import habitTracker.KPI.KPIProxyCapabilityRepository;
import habitTracker.KPI.KPIRepository;
import habitTracker.auth.SecurityUtils;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceService;
import habitTracker.relay.RunnerCollectorClient;
import habitTracker.relay.UplinkRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KpiAutomateDevicesControllerTest {

    static final String BUNDLE = "{\"manifest\":{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"min\",\"aggregation\":\"sum\","
            + "\"combine\":\"sum\",\"variants\":{\"windows\":{\"requires\":[{\"manager\":\"winget\",\"id\":\"ActivityWatch.ActivityWatch\","
            + "\"why\":\"tracks app usage\"}],\"sources\":[{\"type\":\"activitywatch\"}]},\"linux\":{\"sources\":[{\"type\":\"file\"}]}}},"
            + "\"files\":{\"collector.py\":\"print(1)\"}}";

    DeviceService devices = mock(DeviceService.class);
    UplinkRegistry uplinks = mock(UplinkRegistry.class);
    RunnerCollectorClient runner = mock(RunnerCollectorClient.class);
    KPIRepository kpis = mock(KPIRepository.class);
    KPIProxyCapabilityRepository caps = mock(KPIProxyCapabilityRepository.class);
    KpiAutomateDevicesController ctl = new KpiAutomateDevicesController(devices, uplinks, runner, kpis, caps);
    MockedStatic<SecurityUtils> sec;

    @BeforeEach
    void setUp() {
        sec = mockStatic(SecurityUtils.class);
        sec.when(SecurityUtils::getCurrentUserId).thenReturn("u1");
    }

    @AfterEach
    void tearDown() { sec.close(); }

    static Device dev(String id, String name) {
        Device d = new Device();
        d.setId(id);
        d.setName(name);
        return d;
    }

    @Test
    void listsOwnersDevicesAndOfflineOsIsNull() {
        when(devices.list("u1")).thenReturn(List.of(dev("a", "Desktop"), dev("b", "Laptop")));
        when(uplinks.isOnline("a")).thenReturn(true);
        when(uplinks.isOnline("b")).thenReturn(false);
        when(runner.info("u1", "a")).thenReturn(new RunnerCollectorClient.RunnerInfo("windows", "amd64", List.of()));

        var body = ctl.list().getBody();
        assertEquals(2, body.size());
        assertEquals(new KpiAutomateDevicesController.DeviceChip("a", "Desktop", "windows", true), body.get(0));
        assertNull(body.get(1).os());
        assertFalse(body.get(1).online());
        verify(devices).list("u1");
        verify(runner, never()).info(any(), eq("b"));
    }

    @Test
    void osNullWhenInfoCallFails() {
        when(devices.list("u1")).thenReturn(List.of(dev("a", "Desktop")));
        when(uplinks.isOnline("a")).thenReturn(true);
        when(runner.info("u1", "a")).thenThrow(new IllegalStateException("gone"));
        assertNull(ctl.list().getBody().get(0).os());
    }

    @Test
    void planOfSomeoneElsesKpiIs404() {
        when(kpis.findByNameAndUserId("K", "u1")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, ctl.plan("K").getStatusCode());
        verifyNoInteractions(caps);
    }

    @Test
    void planListsRunsOnAndPerOsInstalls() {
        KPI k = new KPI();
        k.setId("k1");
        when(kpis.findByNameAndUserId("K", "u1")).thenReturn(Optional.of(k));
        when(caps.findFirstByKpiIdAndUserIdOrderByVersionDesc("k1", "u1")).thenReturn(Optional.of(
                KPIProxyCapability.builder().collectorBundle(BUNDLE).targetDeviceIds(List.of("a", "foreign")).build()));
        when(devices.owned("u1", "a")).thenReturn(Optional.of(dev("a", "Desktop")));
        when(devices.owned("u1", "foreign")).thenReturn(Optional.empty());
        when(uplinks.isOnline("a")).thenReturn(false);

        var plan = ctl.plan("K").getBody();
        assertEquals(1, plan.runsOn().size());
        assertEquals("Desktop", plan.runsOn().get(0).name());
        assertEquals(2, plan.variants().size());
        var win = plan.variants().get(0);
        assertEquals("windows", win.os());
        assertEquals("ActivityWatch.ActivityWatch", win.installs().get(0).id());
        assertTrue(plan.variants().get(1).installs().isEmpty());
    }
}
