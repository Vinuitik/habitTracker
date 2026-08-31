package habitTracker.updater;

import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIDataSource;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.KPIService;
import habitTracker.KPI.ProxyProvider;
import habitTracker.KPI.ProxyProviderRegistry;
import habitTracker.KPI.ProxyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KPIProxyFillServiceTest {

    @Mock KPIRepository kpiRepository;
    @Mock KPIService kpiService;
    @Mock ProxyProviderRegistry registry;
    @Mock ProxyProvider trelloProvider;

    private KPIProxyFillService service() {
        return new KPIProxyFillService(kpiRepository, kpiService, registry);
    }

    // Regression: zero behavior change for existing manual-only KPIs. NONE (the default),
    // MANUAL_PROMPT, and a null proxyType (pre-M1 doc) must all be left completely alone —
    // no registry lookup, no KPIService call.
    @Test
    void fillFromProxies_leavesNoneAndManualPromptAndNullProxyTypeKPIsUntouched() {
        KPI noneKpi = KPI.builder().id("k1").userId("alice").name("A").proxyType(ProxyType.NONE).build();
        KPI manualPromptKpi = KPI.builder().id("k2").userId("alice").name("B").proxyType(ProxyType.MANUAL_PROMPT).build();
        KPI legacyKpi = KPI.builder().id("k3").userId("alice").name("C").proxyType(null).build();
        when(kpiRepository.findByActive(true)).thenReturn(List.of(noneKpi, manualPromptKpi, legacyKpi));

        service().fillFromProxies();

        verifyNoInteractions(registry);
        verifyNoInteractions(kpiService);
    }

    @Test
    void fillFromProxies_resolvesProviderAndWritesValue_forConfiguredProxyType() {
        KPI trelloKpi = KPI.builder().id("k1").userId("alice").name("Cards").proxyType(ProxyType.TRELLO_CARD_COUNT).build();
        when(kpiRepository.findByActive(true)).thenReturn(List.of(trelloKpi));
        when(registry.resolve(ProxyType.TRELLO_CARD_COUNT)).thenReturn(Optional.of(trelloProvider));
        when(trelloProvider.fetchValue(eq(trelloKpi), any())).thenReturn(Optional.of(5.0));

        service().fillFromProxies();

        LocalDate yesterday = LocalDate.now().minusDays(1);
        verify(kpiService).addKPIDataForUser("alice", "Cards", yesterday, 5.0, KPIDataSource.PROXY_TRELLO);
    }

    @Test
    void fillFromProxies_providerReturnsEmpty_doesNotWrite() {
        KPI trelloKpi = KPI.builder().id("k1").userId("alice").name("Cards").proxyType(ProxyType.TRELLO_CARD_COUNT).build();
        when(kpiRepository.findByActive(true)).thenReturn(List.of(trelloKpi));
        when(registry.resolve(ProxyType.TRELLO_CARD_COUNT)).thenReturn(Optional.of(trelloProvider));
        when(trelloProvider.fetchValue(eq(trelloKpi), any())).thenReturn(Optional.empty());

        service().fillFromProxies();

        verifyNoInteractions(kpiService);
    }

    @Test
    void fillFromProxies_noRegisteredProviderForType_skipsGracefully() {
        KPI trelloKpi = KPI.builder().id("k1").userId("alice").name("Cards").proxyType(ProxyType.TRELLO_CARD_COUNT).build();
        when(kpiRepository.findByActive(true)).thenReturn(List.of(trelloKpi));
        when(registry.resolve(ProxyType.TRELLO_CARD_COUNT)).thenReturn(Optional.empty());

        service().fillFromProxies();

        verifyNoInteractions(kpiService);
    }

    @Test
    void fillFromProxies_mixOfKpis_onlyTouchesTheConfiguredOne() {
        KPI manualKpi = KPI.builder().id("k1").userId("bob").name("Weight").proxyType(ProxyType.NONE).build();
        KPI trelloKpi = KPI.builder().id("k2").userId("alice").name("Cards").proxyType(ProxyType.TRELLO_CARD_COUNT).build();
        when(kpiRepository.findByActive(true)).thenReturn(List.of(manualKpi, trelloKpi));
        when(registry.resolve(ProxyType.TRELLO_CARD_COUNT)).thenReturn(Optional.of(trelloProvider));
        when(trelloProvider.fetchValue(eq(trelloKpi), any())).thenReturn(Optional.of(3.0));

        service().fillFromProxies();

        LocalDate yesterday = LocalDate.now().minusDays(1);
        verify(kpiService).addKPIDataForUser("alice", "Cards", yesterday, 3.0, KPIDataSource.PROXY_TRELLO);
        verify(kpiService, never()).addKPIDataForUser(eq("bob"), anyString(), any(), any(), any());
    }
}
