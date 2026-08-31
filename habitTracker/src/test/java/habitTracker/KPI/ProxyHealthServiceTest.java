package habitTracker.KPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M12: unit coverage for the proxy maintenance loop's circuit breaker (consecutive failures) and
 * anomaly check, independent of the nightly orchestration in KPIProxyFillService (covered
 * separately in habitTracker.updater.KPIProxyFillServiceTest).
 */
@ExtendWith(MockitoExtension.class)
class ProxyHealthServiceTest {

    private static final int THRESHOLD = 3;

    @Mock KPIRepository kpiRepository;
    @Mock DynamicKPIDataRepository dynamicKPIDataRepository;
    @Mock CapabilityRepairTrigger repairTrigger;

    // Real instance — pure function, no dependencies, cheaper than stubbing toCollectionName on
    // every test.
    private final KPICollectionNameUtil collectionNameUtil = new KPICollectionNameUtil();

    private ProxyHealthService service;

    @BeforeEach
    void setUp() {
        service = new ProxyHealthService(kpiRepository, dynamicKPIDataRepository, collectionNameUtil,
                repairTrigger, THRESHOLD);
    }

    private KPI kpi() {
        return KPI.builder().id("k1").userId("alice").name("Cards")
                .proxyType(ProxyType.TRELLO_CARD_COUNT).build();
    }

    // --- consecutive-failure circuit breaker ---------------------------------------------------

    @Test
    void recordFailure_singleTransientFailure_doesNotTripBreaker() {
        KPI kpi = kpi();

        service.recordFailure(kpi);

        assertEquals(1, kpi.getConsecutiveProxyFailures());
        assertEquals(ProxyStatus.ACTIVE, kpi.getProxyStatus());
        verifyNoInteractions(repairTrigger);
    }

    @Test
    void recordFailure_reachesConfiguredThreshold_tripsBreakerAndFiresRepairTrigger() {
        KPI kpi = kpi();

        service.recordFailure(kpi);
        service.recordFailure(kpi);
        service.recordFailure(kpi);

        assertEquals(3, kpi.getConsecutiveProxyFailures());
        assertEquals(ProxyStatus.NEEDS_REPAIR, kpi.getProxyStatus());
        verify(repairTrigger).attemptRepair(kpi);
        verify(kpiRepository, times(3)).save(kpi);
    }

    @Test
    void recordFailure_belowThreshold_staysActiveAndNeverCallsRepairTrigger() {
        KPI kpi = kpi();

        service.recordFailure(kpi);
        service.recordFailure(kpi);

        assertEquals(2, kpi.getConsecutiveProxyFailures());
        assertEquals(ProxyStatus.ACTIVE, kpi.getProxyStatus());
        verifyNoInteractions(repairTrigger);
    }

    @Test
    void recordSuccess_inBetweenFailures_resetsCounterToZero_soFullThresholdIsRequiredAgain() {
        KPI kpi = kpi();

        service.recordFailure(kpi);
        service.recordFailure(kpi);
        service.recordSuccess(kpi);

        assertEquals(0, kpi.getConsecutiveProxyFailures());
        assertEquals(ProxyStatus.ACTIVE, kpi.getProxyStatus());

        // Two more failures after the reset must NOT trip the breaker yet — a fresh 3 in a row
        // are required, the earlier ones don't carry over.
        service.recordFailure(kpi);
        service.recordFailure(kpi);
        assertEquals(ProxyStatus.ACTIVE, kpi.getProxyStatus());
        verifyNoInteractions(repairTrigger);

        service.recordFailure(kpi);
        assertEquals(ProxyStatus.NEEDS_REPAIR, kpi.getProxyStatus());
        verify(repairTrigger).attemptRepair(kpi);
    }

    @Test
    void needsRepair_reflectsCurrentProxyStatus_defaultsToFalseWhenNull() {
        KPI activeKpi = kpi();
        activeKpi.setProxyStatus(null);
        assertFalse(service.needsRepair(activeKpi));

        KPI brokenKpi = kpi();
        brokenKpi.setProxyStatus(ProxyStatus.NEEDS_REPAIR);
        assertTrue(service.needsRepair(brokenKpi));
    }

    // --- anomaly check ---------------------------------------------------------------------

    @Test
    void isAnomalous_notEnoughHistory_returnsFalse() {
        KPI kpi = kpi();
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(anyInt(), anyString()))
                .thenReturn(dataPoints(10.0, 11.0)); // only 2 points, below ANOMALY_MIN_HISTORY

        assertFalse(service.isAnomalous(kpi, 500.0));
    }

    @Test
    void isAnomalous_valueWithinHistoricalRange_returnsFalse() {
        KPI kpi = kpi();
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(anyInt(), anyString()))
                .thenReturn(dataPoints(10.0, 12.0, 11.0, 9.0, 10.0));

        assertFalse(service.isAnomalous(kpi, 11.5));
    }

    @Test
    void isAnomalous_valueFarOutsideHistoricalRange_returnsTrue_evenThoughFetchSucceeded() {
        KPI kpi = kpi();
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(anyInt(), anyString()))
                .thenReturn(dataPoints(10.0, 12.0, 11.0, 9.0, 10.0));

        // Historical range is roughly [9, 12] — 500 is wildly outside it, no exception/empty
        // involved at all, this is purely a "value looks wrong" signal.
        assertTrue(service.isAnomalous(kpi, 500.0));
    }

    @Test
    void isAnomalous_flatHistory_stillCatchesWildOutlierViaFallbackMargin() {
        KPI kpi = kpi();
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(anyInt(), anyString()))
                .thenReturn(dataPoints(10.0, 10.0, 10.0, 10.0));

        assertTrue(service.isAnomalous(kpi, 1000.0));
        assertFalse(service.isAnomalous(kpi, 10.2)); // small drift tolerated
    }

    @Test
    void flagAnomaly_setsNeedsRepair_savesAndFiresRepairTrigger_withoutTouchingFailureCounter() {
        KPI kpi = kpi();
        kpi.setConsecutiveProxyFailures(1);

        service.flagAnomaly(kpi);

        assertEquals(ProxyStatus.NEEDS_REPAIR, kpi.getProxyStatus());
        assertEquals(1, kpi.getConsecutiveProxyFailures()); // anomaly path doesn't touch this
        verify(kpiRepository).save(kpi);
        verify(repairTrigger).attemptRepair(kpi);
    }

    private List<KPIData> dataPoints(double... values) {
        LocalDate date = LocalDate.now();
        return java.util.stream.IntStream.range(0, values.length)
                .mapToObj(i -> KPIData.builder().date(date.minusDays(i)).value(values[i]).build())
                .toList();
    }
}
