package habitTracker.updater;

import habitTracker.KPI.KPI;
import habitTracker.KPI.KPIRepository;
import habitTracker.KPI.KPIService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Fills in yesterday's value for any KPI that opted into auto-fill (KPI.autoFillEnabled) and has
 * no data point for that date yet — i.e. the user forgot to log it. Scans across all users the
 * same way HabitUpdateService does for habits (no SecurityUtils/request context on a cron thread),
 * but every write goes through KPIService.fillDefaultIfMissing, which is scoped to that KPI's own
 * id-keyed collection — one user's missed KPI never touches another user's data.
 */
@Service
public class KPIDefaultFillService {

    private final KPIRepository kpiRepository;
    private final KPIService kpiService;

    public KPIDefaultFillService(KPIRepository kpiRepository, KPIService kpiService) {
        this.kpiRepository = kpiRepository;
        this.kpiService = kpiService;
    }

    public void fillMissingDefaults() {
        LocalDate targetDate = LocalDate.now().minusDays(1);
        List<KPI> candidates = kpiRepository.findByActiveAndAutoFillEnabled(true, true);
        int filled = 0;
        for (KPI kpi : candidates) {
            if (kpiService.fillDefaultIfMissing(kpi, targetDate)) {
                filled++;
            }
        }
        System.out.println("KPI default-fill: " + filled + "/" + candidates.size()
                + " KPI(s) filled for " + targetDate);
    }
}
