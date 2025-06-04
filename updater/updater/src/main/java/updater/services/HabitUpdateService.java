package updater.services;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import updater.models.Habit;
import updater.models.HabitStructure;

import java.time.LocalDate;
import java.util.List;

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
        List<Habit> habits = mongoTemplate.findAll(Habit.class);
        
        System.out.println(habits.size() + " habits found.");

        for (Habit habit : habits) {
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