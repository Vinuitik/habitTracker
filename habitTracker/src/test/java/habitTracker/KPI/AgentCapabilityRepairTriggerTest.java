package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class AgentCapabilityRepairTriggerTest {

    CapabilityAgentService service = mock(CapabilityAgentService.class);
    CapabilityGenerator generator = mock(CapabilityGenerator.class);
    KPIRepository kpis = mock(KPIRepository.class);
    // Direct executor: runs inline so the test is deterministic.
    AgentCapabilityRepairTrigger trigger = new AgentCapabilityRepairTrigger(service, generator, kpis, Runnable::run);

    private static KPI collectorKpi() {
        return KPI.builder().id("k").userId("u").proxyType(ProxyType.RUNNER_COLLECTOR)
                .proxyStatus(ProxyStatus.NEEDS_REPAIR).build();
    }

    private static KPIProxyCapability waitingResult() {
        return KPIProxyCapability.builder().version(2).status(CapabilityStatus.TEST_FAILED)
                .failureReason(CapabilityAgentService.NO_RUNNER_REASON).build();
    }

    @Test
    void collectorKpi_triggersRepairWithAutoRetry() {
        KPI kpi = collectorKpi();
        when(generator.runnerAvailable("u")).thenReturn(true);
        when(service.repairFor("u", kpi, true)).thenReturn(KPIProxyCapability.builder()
                .version(2).status(CapabilityStatus.ACTIVE).build());
        trigger.attemptRepair(kpi);
        verify(service).repairFor("u", kpi, true);
        assertTrue(trigger.waitingKpiIds().isEmpty());
    }

    @Test
    void nonCollectorKpi_isNotRepaired() {
        trigger.attemptRepair(KPI.builder().id("k").userId("u").proxyType(ProxyType.TRELLO_CARD_COUNT).build());
        verifyNoInteractions(service, generator);
    }

    @Test
    void repairFailureNeverPropagates() {
        when(generator.runnerAvailable("u")).thenReturn(true);
        when(service.repairFor(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("nope"));
        trigger.attemptRepair(collectorKpi()); // must not throw into the nightly job
    }

    @Test
    void noRunnerOnline_parksWithoutRunningRepair_thenResumesWhenRunnerConnects() {
        KPI kpi = collectorKpi();
        when(generator.runnerAvailable("u")).thenReturn(false);
        trigger.attemptRepair(kpi);
        verifyNoInteractions(service);
        assertEquals(Set.of("k"), trigger.waitingKpiIds());

        trigger.retryWaiting(); // still offline: stays parked
        verifyNoInteractions(service);
        assertEquals(Set.of("k"), trigger.waitingKpiIds());

        when(generator.runnerAvailable("u")).thenReturn(true);
        when(kpis.findById("k")).thenReturn(Optional.of(kpi));
        when(service.repairFor("u", kpi, true)).thenReturn(KPIProxyCapability.builder()
                .version(2).status(CapabilityStatus.ACTIVE).build());
        trigger.retryWaiting();
        verify(service).repairFor("u", kpi, true);
        assertTrue(trigger.waitingKpiIds().isEmpty());
    }

    @Test
    void runnerDropsMidRepair_isParkedAgain() {
        KPI kpi = collectorKpi();
        when(generator.runnerAvailable("u")).thenReturn(true);
        when(service.repairFor("u", kpi, true)).thenReturn(waitingResult());
        trigger.attemptRepair(kpi);
        assertEquals(Set.of("k"), trigger.waitingKpiIds());
    }

    @Test
    void parkedKpiNoLongerNeedingRepair_isDropped() {
        when(generator.runnerAvailable("u")).thenReturn(false);
        trigger.attemptRepair(collectorKpi());
        when(generator.runnerAvailable("u")).thenReturn(true);
        when(kpis.findById("k")).thenReturn(Optional.of(KPI.builder().id("k").userId("u")
                .proxyType(ProxyType.RUNNER_COLLECTOR).proxyStatus(ProxyStatus.ACTIVE).build()));
        trigger.retryWaiting();
        verifyNoInteractions(service);
        assertTrue(trigger.waitingKpiIds().isEmpty());
    }
}
