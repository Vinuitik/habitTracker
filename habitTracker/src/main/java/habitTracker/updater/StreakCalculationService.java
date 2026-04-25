package habitTracker.updater;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructure;

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

        if (lastRunDate == null) {
            lastRunDate = today.minusDays(1);
        }

        LocalDate startDate = lastRunDate.minusDays(1);

        System.out.println("Updating streaks from " + startDate + " to " + today);

        Query activeHabitsQuery = new Query(Criteria.where("active").is(true));
        List<Habit> activeHabits = mongoTemplate.find(activeHabitsQuery, Habit.class);

        if (activeHabits.isEmpty()) {
            System.out.println("No active habits found. Skipping streak update.");
            return;
        }

        Map<Integer, Map<LocalDate, HabitStructure>> structuresByHabitAndDate =
                fetchHabitStructures(activeHabits, startDate, today);

        for (Habit habit : activeHabits) {
            updateHabitStreak(habit, startDate, today, structuresByHabitAndDate);
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
        Integer longestStreak = habit.getLongestStreak() != null ? habit.getLongestStreak() : 0;
        LocalDate habitStartDate = habit.getStartDate();

        if (habitStartDate != null && habitStartDate.isAfter(today)) {
            return;
        }

        Map<LocalDate, HabitStructure> habitStructures =
                structuresByHabitAndDate.getOrDefault(habitId, new HashMap<>());

        LocalDate currentDate = lastRunDate;
        while (!currentDate.isEqual(today) && !currentDate.isAfter(today)) {
            if (habitDateCalculator.shouldTrackHabitOnDate(habit, currentDate)) {
                HabitStructure structure = habitStructures.get(currentDate);
                boolean inferredFromDefault = structure == null;
                boolean completedForDay;

                if (inferredFromDefault) {
                    completedForDay = Boolean.TRUE.equals(habit.getDefaultMade());
                } else {
                    completedForDay = Boolean.TRUE.equals(structure.getCompleted());
                }

                if (completedForDay) {
                    if (inferredFromDefault) {
                        currentStreak++;
                        System.out.println("Habit #" + habitId + " inferred completed on " + currentDate +
                                " from defaultMade=true. Streak increased to " + currentStreak);
                    } else {
                        currentStreak = 1;
                        System.out.println("Habit #" + habitId + " completed on " + currentDate +
                                ". Streak reset to 1");
                    }
                    if (currentStreak > longestStreak) {
                        longestStreak = currentStreak;
                    }
                } else {
                    if (currentStreak > 0) {
                        currentStreak = 0;
                    } else {
                        currentStreak--;
                    }
                    System.out.println("Habit #" + habitId + " not completed on " + currentDate +
                            ". Streak decreased to " + currentStreak);
                }
            } else {
                System.out.println("Habit #" + habitId + " not scheduled for " + currentDate +
                        ". Keeping streak at " + currentStreak);
            }
            currentDate = currentDate.plusDays(1);
        }

        Query updateQuery = new Query(Criteria.where("id").is(habitId));
        Update update = new Update()
                .set("streak", currentStreak)
                .set("longestStreak", longestStreak);
        mongoTemplate.updateFirst(updateQuery, update, Habit.class);

        System.out.println("Updated streak for habit #" + habitId + " to " + currentStreak +
                " (longest: " + longestStreak + ")");
    }
}
