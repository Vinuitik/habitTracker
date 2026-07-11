package habitTracker.updater;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;

@Component
public class UpdateScheduler {

    private final LastRunDateService lastRunDateService;
    private final HabitUpdateService habitUpdateService;

    public UpdateScheduler(LastRunDateService lastRunDateService,
                           HabitUpdateService habitUpdateService) {
        this.lastRunDateService = lastRunDateService;
        this.habitUpdateService = habitUpdateService;
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
            // Single unified pass: rolls each habit's grace window forward, crediting/docking the
            // streak and advancing curDate as occurrences resolve.
            habitUpdateService.updateAllHabits();
            System.out.println("Updater ran successfully for " + java.time.LocalDate.now());
        } catch (Exception e) {
            System.err.println("Error during daily update: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
