package updater;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;

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

        // Update the last run date
        mongoTemplate.save(new LastRunDate(today));

        // Fetch all habits
        List<Habit> habits = mongoTemplate.findAll(Habit.class);

        System.out.println(habits.size() + " habits found.");

        for (Habit habit : habits) {
            LocalDate endDate = habit.getEndDate();
            LocalDate curDate = habit.getCurDate();
            LocalDate startDate = habit.getStartDate();
            Boolean isActive = habit.getActive();
            int frequency = habit.getFrequency();

            if(!isActive) {
                continue;
            }

            // Skip if today is after the end date
            if (endDate != null && today.isAfter(endDate)) {
                continue;
            }

            // Save traceability if curDate is already today
            if (curDate != null && curDate.isEqual(today) ) {
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
                LocalDate newCurDate = null;
                if(offset == frequency) {
                    newCurDate = today;
                }else{
                    newCurDate = today.plusDays(offset);
                }

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

        // Update streaks for all habits
        updateStreaks(lastRunDate, today);

        
        System.out.println("Updater ran successfully for " + today);
    }

    /**
     * Updates the streak for all active habits over a specific date range
     * 
     * @param lastRunDate The date when the updater last ran
     * @param today Today's date
     */
    private void updateStreaks(LocalDate lastRunDate, LocalDate today) {
        // If this is the first run, set lastRunDate to yesterday to process just today
        if (lastRunDate == null) {
            lastRunDate = today.minusDays(1);
        }

        LocalDate startDate = lastRunDate.minusDays(1); // We need yesterday's data for continuity
        
        System.out.println("Updating streaks from " + startDate + " to " + today);
        
        // Get all active habits
        Query activeHabitsQuery = new Query(Criteria.where("active").is(true));
        List<Habit> activeHabits = mongoTemplate.find(activeHabitsQuery, Habit.class);
        
        if (activeHabits.isEmpty()) {
            System.out.println("No active habits found. Skipping streak update.");
            return;
        }
        
        // Get all habit IDs
        List<Integer> habitIds = activeHabits.stream()
                                            .map(Habit::getId)
                                            .collect(Collectors.toList());
        
        // Fetch all habit structures for these habits in the given date range in a single query
        Query structuresQuery = new Query(
            Criteria.where("habitId").in(habitIds)
                   .and("structureDate").gte(startDate).lte(today)
        );
        List<HabitStructure> allStructures = mongoTemplate.find(structuresQuery, HabitStructure.class);
        
        // Group structures by habit ID and date for efficient lookup
        Map<Integer, Map<LocalDate, HabitStructure>> structuresByHabitAndDate = new HashMap<>();
        
        for (HabitStructure structure : allStructures) {
            structuresByHabitAndDate
                .computeIfAbsent(structure.getHabitId(), k -> new HashMap<>())
                .put(structure.getStructureDate(), structure);
        }
        
        // Process each habit
        for (Habit habit : activeHabits) {
            Integer habitId = habit.getId();
            int frequency = habit.getFrequency();
            Integer currentStreak = habit.getStreak() != null ? habit.getStreak() : 0;
            LocalDate habitStartDate = habit.getStartDate();
            
            // Skip if habit isn't due to be tracked in this period
            if (habitStartDate != null && habitStartDate.isAfter(today)) {
                continue;
            }
            
            // Get the habit's structures
            Map<LocalDate, HabitStructure> habitStructures = 
                structuresByHabitAndDate.getOrDefault(habitId, new HashMap<>());
            
            // Iterate through each day in the range
            LocalDate currentDate = lastRunDate;
            while (!currentDate.isEqual(today)&&!currentDate.isAfter(today)) {
                // Check if the habit should be tracked on this day based on frequency
                if (shouldTrackHabitOnDate(habit, currentDate)) {
                    HabitStructure structure = habitStructures.get(currentDate);
                    
                    if (structure != null && Boolean.TRUE.equals(structure.getCompleted())) {
                        // Habit was completed - increase streak
                        currentStreak++;
                        System.out.println("Habit #" + habitId + " completed on " + currentDate + 
                                          ". Streak increased to " + currentStreak);
                    } else {
                        // Habit was not completed or not found - reset streak
                        currentStreak = 0;
                        System.out.println("Habit #" + habitId + " not completed on " + currentDate + 
                                          ". Streak reset to 0");
                    }
                } else {
                    System.out.println("Habit #" + habitId + " not scheduled for " + currentDate + 
                                      ". Keeping streak at " + currentStreak);
                }
                
                // Move to next day
                currentDate = currentDate.plusDays(1);
            }
            
            // Update the habit's streak in the database
            habit.setStreak(currentStreak);
            mongoTemplate.save(habit);
            System.out.println("Updated streak for habit #" + habitId + " to " + currentStreak);
        }
        
        System.out.println("Streak update completed for all habits");
    }
    
    /**
     * Determines if a habit should be tracked on a specific date based on its frequency
     * 
     * @param habit The habit to check
     * @param date The date to check
     * @return true if the habit should be tracked on this date, false otherwise
     */
    private boolean shouldTrackHabitOnDate(Habit habit, LocalDate date) {
        LocalDate startDate = habit.getStartDate();
        int frequency = habit.getFrequency();
        
        // If the date is before the habit start date, don't track
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        
        // If the end date is set and the date is after it, don't track
        LocalDate endDate = habit.getEndDate();
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        
        // For daily habits (frequency=1), always track
        if (frequency == 1) {
            return true;
        }
        
        // For other frequencies, check if this is a day the habit should be tracked
        long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(startDate, date);
        return daysSinceStart % frequency == 0;
    }
}
