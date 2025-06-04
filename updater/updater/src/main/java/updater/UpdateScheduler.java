package updater;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.PostConstruct;
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
        System.out.println("Running updater on startup...");
        System.out.println("Current time zone: " + LocalDateTime.now());
        performDailyUpdate();
    }

    @Scheduled(cron = "0 5 0 * * ?") // 12:05 AM daily
    public void scheduledUpdate() {
        performDailyUpdate();
    }

    private void performDailyUpdate() {
        try {
            // Check if already ran today
            if (lastRunDateService.hasRunToday()) {
                System.out.println("Updater already ran today. Skipping execution.");
                return;
            }

            // Mark as run today
            lastRunDateService.markRunToday();

            // Update habits
            habitUpdateService.updateAllHabits();

            // Update streaks
            streakCalculationService.updateAllStreaks();

            System.out.println("Updater ran successfully for " + java.time.LocalDate.now());
        } catch (Exception e) {
            System.err.println("Error during daily update: " + e.getMessage());
            e.printStackTrace();
        }
    }
}