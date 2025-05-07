package updater;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import updater.models.Habit;
import updater.models.HabitStructure;
import updater.models.LastRunDate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;

import javax.annotation.PostConstruct;

import java.time.ZoneId;
import java.util.Date;

@SpringBootApplication
@EnableScheduling // Enable scheduling for the application
public class Updater {
    public static void main(String[] args) {
        SpringApplication.run(Updater.class, args);
    }
}

@Component
class HabitUpdater {

    
    private final MongoTemplate mongoTemplate;

    public HabitUpdater(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void runOnStartup() {
        System.out.println("Running updater on startup...");
        System.out.println("Current time zone: " + LocalDateTime.now());
        updateHabits();
    }

    // Schedule the updater to run every day at midnight
    @Scheduled(cron = "0 5 0 * * ?") // Cron expression for 12:00 AM daily
    public void updateHabits() {
        LocalDate today = LocalDate.now();

        // Find the maximum date in the last_run_date collection
        // Updated to look for _id field instead of date field
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group().max("_id").as("date")
        );
        AggregationResults<Document> result = mongoTemplate.aggregate(
                aggregation, "last_run_date", Document.class);
        
        Document resultDoc = result.getUniqueMappedResult();
        LocalDate lastRunDate = null;
        
        if (resultDoc != null && resultDoc.get("date") != null) {
            Date dbDate = resultDoc.get("date", Date.class);
            if (dbDate != null) {
                lastRunDate = dbDate.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            }
        }
        System.out.println("Last run date: " + lastRunDate);
        System.out.println("Today's date: " + today);

        // Check if the updater has already run today
        if (lastRunDate != null && lastRunDate.isEqual(today)) {
            System.out.println("Updater already ran today. Skipping execution.");
            return;
        }

        // Fetch all habits
        List<Habit> habits = mongoTemplate.findAll(Habit.class);

        System.out.println(habits.size() + " habits found.");

        for (Habit habit : habits) {
            LocalDate endDate = habit.getEndDate();
            LocalDate curDate = habit.getCurDate();
            LocalDate startDate = habit.getStartDate();
            int frequency = habit.getFrequency();

            // Skip if today is after the end date
            if (endDate != null && today.isAfter(endDate)) {
                continue;
            }

            // Save traceability if curDate is already today
            if (curDate != null && curDate.isEqual(today)) {
                HabitStructure habitStructure = HabitStructure.builder()
                        .habitId(habit.getId())
                        .structureDate(today)
                        .completed(false)
                        .build();
                System.out.println("1"+habitStructure);
                mongoTemplate.save(habitStructure);
                continue;
            }

            // Skip if curDate is later than today
            if (curDate != null && curDate.isAfter(today)) {
                continue;
            }

            // Calculate the new curDate
            if (curDate != null && curDate.isBefore(today)) {
                int daysSinceStart = (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, today);
                int offset = frequency - (daysSinceStart % frequency);
                LocalDate newCurDate = today.plusDays(offset);

                // Update the habit's curDate
                habit.setCurDate(newCurDate);
                System.out.println(habit);
                mongoTemplate.save(habit);

                // Save traceability in HabitStructure if newCurDate is today
                if (newCurDate.equals(today)) {
                    HabitStructure habitStructure = HabitStructure.builder()
                            .habitId(habit.getId())
                            .structureDate(today)
                            .completed(false)
                            .build();
                    System.out.println("2"+habitStructure);
                    mongoTemplate.save(habitStructure);
                }
            }
        }

        // Update the last run date
        mongoTemplate.save(new LastRunDate(today));
        System.out.println("Updater ran successfully for " + today);
    }
}
