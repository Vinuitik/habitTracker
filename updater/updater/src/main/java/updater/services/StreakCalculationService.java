package updater.services;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import updater.models.Habit;
import updater.models.HabitStructure;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StreakCalculationService {
    
    private final MongoTemplate mongoTemplate;
    private final LastRunDateService lastRunDateService;
    private final HabitDateCalculator habitDateCalculator;

    public StreakCalculationService(MongoTemplate mongoTemplate, 
                                   LastRunDateService lastRunDateService,
                                   HabitDateCalculator habitDateCalculator) {
        this.mongoTemplate = mongoTemplate;
        this.lastRunDateService = lastRunDateService;
        this.habitDateCalculator = habitDateCalculator;
    }

    public void updateAllStreaks() {
        LocalDate today = LocalDate.now();
        LocalDate lastRunDate = lastRunDateService.getLastRunDate();
        
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
        
        // Get habit structures for the date range
        Map<Integer, Map<LocalDate, HabitStructure>> structuresByHabitAndDate = 
            fetchHabitStructures(activeHabits, startDate, today);
        
        // Process each habit
        for (Habit habit : activeHabits) {
            updateHabitStreak(habit, lastRunDate, today, structuresByHabitAndDate);
        }
        
        System.out.println("Streak update completed for all habits");
    }

    private Map<Integer, Map<LocalDate, HabitStructure>> fetchHabitStructures(
            List<Habit> activeHabits, LocalDate startDate, LocalDate endDate) {
        
        List<Integer> habitIds = activeHabits.stream()
                                           .map(Habit::getId)
                                           .collect(Collectors.toList());
        
        Query structuresQuery = new Query(
            Criteria.where("habitId").in(habitIds)
                   .and("structureDate").gte(startDate).lte(endDate)
        );
        List<HabitStructure> allStructures = mongoTemplate.find(structuresQuery, HabitStructure.class);
        
        Map<Integer, Map<LocalDate, HabitStructure>> structuresByHabitAndDate = new HashMap<>();
        
        for (HabitStructure structure : allStructures) {
            structuresByHabitAndDate
                .computeIfAbsent(structure.getHabitId(), k -> new HashMap<>())
                .put(structure.getStructureDate(), structure);
        }
        
        return structuresByHabitAndDate;
    }

    private void updateHabitStreak(Habit habit, LocalDate lastRunDate, LocalDate today,
                                  Map<Integer, Map<LocalDate, HabitStructure>> structuresByHabitAndDate) {
        
        Integer habitId = habit.getId();
        Integer currentStreak = habit.getStreak() != null ? habit.getStreak() : 0;
        LocalDate habitStartDate = habit.getStartDate();
        
        // Skip if habit isn't due to be tracked in this period
        if (habitStartDate != null && habitStartDate.isAfter(today)) {
            return;
        }
        
        // Get the habit's structures
        Map<LocalDate, HabitStructure> habitStructures = 
            structuresByHabitAndDate.getOrDefault(habitId, new HashMap<>());
        
        // Iterate through each day in the range
        LocalDate currentDate = lastRunDate;
        while (!currentDate.isEqual(today) && !currentDate.isAfter(today)) {
            // Check if the habit should be tracked on this day based on frequency
            if (habitDateCalculator.shouldTrackHabitOnDate(habit, currentDate)) {
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
}