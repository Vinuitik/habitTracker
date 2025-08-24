package updater.services;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import updater.models.Habit;
import updater.models.HabitStructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HabitUpdateService {
    
    private final MongoTemplate mongoTemplate;
    private final HabitDateCalculator habitDateCalculator;
    private final HabitStructureManager habitStructureManager;

    public HabitUpdateService(MongoTemplate mongoTemplate, 
                             HabitDateCalculator habitDateCalculator,
                             HabitStructureManager habitStructureManager) {
        this.mongoTemplate = mongoTemplate;
        this.habitDateCalculator = habitDateCalculator;
        this.habitStructureManager = habitStructureManager;
    }

    public void updateAllHabits() {
        LocalDate today = LocalDate.now();

        // Find habit IDs that already have a structure for today to avoid duplicates
        Query query = new Query(Criteria.where("structureDate").is(today));
        List<HabitStructure> todaysStructures = mongoTemplate.find(query, HabitStructure.class);
        Set<Integer> processedHabitIds = todaysStructures.stream()
                .map(HabitStructure::getHabitId)
                .collect(Collectors.toSet());

        List<Habit> allHabits = mongoTemplate.findAll(Habit.class);

        // Filter out habits that have already been processed today
        List<Habit> habitsToProcess = allHabits.stream()
                .filter(habit -> !processedHabitIds.contains(habit.getId()))
                .collect(Collectors.toList());
        
        System.out.println(habitsToProcess.size() + " habits to process.");

        for (Habit habit : habitsToProcess) {
            processHabit(habit, today);
        }
    }

    private void processHabit(Habit habit, LocalDate today) {
        // Skip inactive habits
        if (!Boolean.TRUE.equals(habit.getActive())) {
            return;
        }

        // Skip if past end date
        if (habit.getEndDate() != null && today.isAfter(habit.getEndDate())) {
            return;
        }

        System.out.println(habit.getName());

        LocalDate curDate = habit.getCurDate();

        // Handle habits already scheduled for today
        if (curDate != null && curDate.isEqual(today)) {
            habitStructureManager.createHabitStructure(habit.getId(), today);
            return;
        }

        // Skip if scheduled for future
        if (curDate != null && curDate.isAfter(today)) {
            return;
        }

        // Calculate and update next occurrence for overdue habits
        if (curDate != null && curDate.isBefore(today)) {
            LocalDate newCurDate = habitDateCalculator.calculateNextOccurrence(habit, today);
            
            habit.setCurDate(newCurDate);
            System.out.println(habit);
            mongoTemplate.save(habit);

            // Create structure if scheduled for today
            if (newCurDate.equals(today)) {
                habitStructureManager.createHabitStructure(habit.getId(), today);
            }
        }
    }
}