package habitTracker.updater;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;

@Component
public class UpdateScheduler {

    private final LastRunDateService lastRunDateService;
    private final HabitUpdateService habitUpdateService;
    private final StreakCalculationService streakCalculationService;

    public UpdateScheduler(LastRunDateService lastRunDateService,
                           HabitUpdateService habitUpdateService,
                           StreakCalculationService streakCalculationService) {
        this.lastRunDateService = lastRunDateService;
        this.habitUpdateService = habitUpdateService;
        this.streakCalculationService = streakCalculationService;
    }

    @PostConstruct
    public void runOnStartup() {
        System.out.println("Running updater on startup at: " + LocalDateTime.now());
        performDailyUpdate();
    }

    @Scheduled(cron = "0 5 0 * * ?")
    public void scheduledUpdate() {
        System.out.println("Scheduled update triggered at: " + LocalDateTime.now());
        performDailyUpdate();
    }

    private void performDailyUpdate() {
        try {
            if (lastRunDateService.hasRunToday()) {
                System.out.println("Updater already ran today. Skipping.");
                return;
            }
            lastRunDateService.markRunToday();
            habitUpdateService.updateAllHabits();
            streakCalculationService.updateAllStreaks();
            System.out.println("Updater ran successfully for " + java.time.LocalDate.now());
        } catch (Exception e) {
            System.err.println("Error during daily update: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
