package habitTracker.updater;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;

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

        Query query = new Query(Criteria.where("structureDate").is(today));
        List<HabitStructure> todaysStructures = mongoTemplate.find(query, HabitStructure.class);
        Set<Integer> processedHabitIds = todaysStructures.stream()
                .map(HabitStructure::getHabitId)
                .collect(Collectors.toSet());

        List<Habit> allHabits = mongoTemplate.findAll(Habit.class);

        List<Habit> habitsToProcess = allHabits.stream()
                .filter(habit -> !processedHabitIds.contains(habit.getId()))
                .collect(Collectors.toList());

        System.out.println(habitsToProcess.size() + " habits to process.");

        for (Habit habit : habitsToProcess) {
            processHabit(habit, today);
        }
    }

    private void processHabit(Habit habit, LocalDate today) {
        if (!Boolean.TRUE.equals(habit.getActive())) {
            return;
        }

        if (habit.getEndDate() != null && today.isAfter(habit.getEndDate())) {
            return;
        }

        System.out.println(habit.getName());

        LocalDate curDate = habit.getCurDate();
        boolean isDefaultMade = habit.getDefaultMade() != null && habit.getDefaultMade();

        if (curDate != null && curDate.isEqual(today)) {
            habitStructureManager.createHabitStructure(habit.getId(), today, isDefaultMade);
            return;
        }

        if (curDate != null && curDate.isAfter(today)) {
            return;
        }

        if (curDate != null && curDate.isBefore(today)) {
            LocalDate newCurDate = habitDateCalculator.calculateNextOccurrence(habit, today);

            Query updateQuery = new Query(Criteria.where("id").is(habit.getId()));
            Update update = new Update().set("curDate", newCurDate);
            mongoTemplate.updateFirst(updateQuery, update, Habit.class);

            System.out.println("Updated curDate for habit " + habit.getName() + " to " + newCurDate);

            if (newCurDate.equals(today)) {
                habitStructureManager.createHabitStructure(habit.getId(), today, isDefaultMade);
            }
        }
    }
}
