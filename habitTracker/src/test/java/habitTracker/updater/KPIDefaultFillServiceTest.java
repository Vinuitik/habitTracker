package habitTracker.updater;

import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.KPIService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KPIDefaultFillServiceTest {

    @Mock KPIRepository kpiRepository;
    @Mock KPIService kpiService;

    private KPIDefaultFillService service() {
        return new KPIDefaultFillService(kpiRepository, kpiService);
    }

    @Test
    void fillMissingDefaults_onlyScansActiveAutoFillEnabledKPIs() {
        when(kpiRepository.findByActiveAndAutoFillEnabled(true, true)).thenReturn(List.of());

        service().fillMissingDefaults();

        verify(kpiRepository).findByActiveAndAutoFillEnabled(true, true);
        verifyNoInteractions(kpiService);
    }

    @Test
    void fillMissingDefaults_targetsYesterday_forEachCandidateKPI() {
        KPI kpi1 = KPI.builder().id("k1").userId("alice").build();
        KPI kpi2 = KPI.builder().id("k2").userId("bob").build();
        when(kpiRepository.findByActiveAndAutoFillEnabled(true, true)).thenReturn(List.of(kpi1, kpi2));

        service().fillMissingDefaults();

        LocalDate yesterday = LocalDate.now().minusDays(1);
        verify(kpiService).fillDefaultIfMissing(kpi1, yesterday);
        verify(kpiService).fillDefaultIfMissing(kpi2, yesterday);
    }

    @Test
    void fillMissingDefaults_oneKPIsFailureDoesNotResultInOthersNotBeingProcessed() {
        // fillDefaultIfMissing itself is @Transactional per-KPI in KPIService; the cron loop here
        // must still attempt every candidate even if an earlier one is a no-op.
        KPI kpi1 = KPI.builder().id("k1").userId("alice").build();
        KPI kpi2 = KPI.builder().id("k2").userId("bob").build();
        when(kpiRepository.findByActiveAndAutoFillEnabled(true, true)).thenReturn(List.of(kpi1, kpi2));
        when(kpiService.fillDefaultIfMissing(eq(kpi1), any())).thenReturn(false);
        when(kpiService.fillDefaultIfMissing(eq(kpi2), any())).thenReturn(true);

        service().fillMissingDefaults();

        verify(kpiService).fillDefaultIfMissing(eq(kpi1), any());
        verify(kpiService).fillDefaultIfMissing(eq(kpi2), any());
    }
}
