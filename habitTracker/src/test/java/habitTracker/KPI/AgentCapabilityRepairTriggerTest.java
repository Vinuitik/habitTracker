package habitTracker.KPI;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class AgentCapabilityRepairTriggerTest {

    CapabilityAgentService service = mock(CapabilityAgentService.class);
    // Direct executor: runs inline so the test is deterministic.
    AgentCapabilityRepairTrigger trigger = new AgentCapabilityRepairTrigger(service, Runnable::run);

    @Test
    void sandboxKpi_triggersRepairWithAutoRetry() {
        KPI kpi = KPI.builder().id("k").userId("u").proxyType(ProxyType.SANDBOX_CAPABILITY).build();
        when(service.repairFor("u", kpi, true)).thenReturn(KPIProxyCapability.builder()
                .version(2).status(CapabilityStatus.ACTIVE).build());
        trigger.attemptRepair(kpi);
        verify(service).repairFor("u", kpi, true);
    }

    @Test
    void nonSandboxKpi_isNotRepaired() {
        trigger.attemptRepair(KPI.builder().id("k").userId("u").proxyType(ProxyType.TRELLO_CARD_COUNT).build());
        verifyNoInteractions(service);
    }

    @Test
    void repairFailureNeverPropagates() {
        KPI kpi = KPI.builder().id("k").userId("u").proxyType(ProxyType.SANDBOX_CAPABILITY).build();
        when(service.repairFor(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("nope"));
        trigger.attemptRepair(kpi); // must not throw into the nightly job
    }
}
