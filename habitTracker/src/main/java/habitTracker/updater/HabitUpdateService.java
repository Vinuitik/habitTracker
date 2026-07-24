package habitTracker.updater;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import habitTracker.Habit.Habit;
import habitTracker.Structure.HabitStructureRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Unified daily engine for the rolling "grace window" model.
 *
 * Each habit's current occurrence spans a window [curDate, curDate + frequency). The habit stays
 * actionable for that whole window; it is only resolved when either (a) it is completed anywhere in
 * the window, or (b) the window fully lapses uncompleted. On resolution the streak is credited/docked
 * and curDate advances one period. This replaces the former split between HabitUpdateService
 * (curDate advancement + structure seeding) and StreakCalculationService (day-by-day streak walk),
 * which had to agree on when a window closes — folding them into one pass removes that coupling.
 *
 * At frequency=1 the window is a single day, so behavior is identical to the previous model:
 * a missed day is docked on the next run ("negative streaks work on the update day").
 */
@Service
public class HabitUpdateService {

    private final MongoTemplate mongoTemplate;
    private final HabitStructureManager habitStructureManager;
    private final HabitStructureRepository habitStructureRepository;

    public HabitUpdateService(MongoTemplate mongoTemplate,
                              HabitStructureManager habitStructureManager,
                              HabitStructureRepository habitStructureRepository) {
        this.mongoTemplate = mongoTemplate;
        this.habitStructureManager = habitStructureManager;
        this.habitStructureRepository = habitStructureRepository;
    }

    public void updateAllHabits() {
        LocalDate today = LocalDate.now();
        List<Habit> allHabits = mongoTemplate.findAll(Habit.class);
        for (Habit habit : allHabits) {
            processHabit(habit, today);
        }
    }

    private void processHabit(Habit habit, LocalDate today) {
        if (!Boolean.TRUE.equals(habit.getActive())) {
            return;
        }
        LocalDate anchor = habit.getCurDate();
        if (anchor == null) {
            return; // misconfigured (no schedule anchor)
        }

        int freq = habit.getFrequency() != null && habit.getFrequency() > 0 ? habit.getFrequency() : 1;
        LocalDate endDate = habit.getEndDate();
        boolean isDefaultMade = Boolean.TRUE.equals(habit.getDefaultMade());

        int streak = habit.getStreak() != null ? habit.getStreak() : 0;
        int longestStreak = habit.getLongestStreak() != null ? habit.getLongestStreak() : 0;

        Integer pendingLastNeg = null;   // set lastNegativeStreak when crediting from <= 0
        boolean clearLastNeg = false;    // unset lastNegativeStreak when a positive run is broken

        // Roll forward through every occurrence that is already resolved.
        while (true) {
            if (endDate != null && anchor.isAfter(endDate)) {
                break; // habit has ended; no further occurrences
            }
            if (today.isBefore(anchor)) {
                break; // current window has not opened yet
            }

            // Materialize the occurrence's seed the moment its window opens. For defaultMade habits
            // the seed is completed=true ("assumed done"); for normal habits it is completed=false.
            // The seed keeps the occurrence visible on the Today page and on the completion table.
            if (!habitStructureRepository.existsByHabitIdAndStructureDate(habit.getId(), anchor)) {
                habitStructureManager.createHabitStructure(habit.getId(), anchor, isDefaultMade, habit.getUserId());
            }

            LocalDate windowLastDay = anchor.plusDays(freq - 1);
            boolean windowClosed = !today.isBefore(anchor.plusDays(freq)); // today >= anchor + freq

            // Resolve this occurrence to SUCCESS / LAPSE / (still) OPEN.
            Boolean success; // null = open/unresolved → stop rolling
            if (isDefaultMade) {
                // "Assumed done unless you relapse." Never resolve the current open window early —
                // a relapse can still land — so only decide once the window has closed.
                if (!windowClosed) {
                    success = null;
                } else {
                    boolean relapsed = habitStructureRepository
                            .existsByHabitIdAndCompletedInWindow(
                                    habit.getId(), Boolean.FALSE, anchor, windowLastDay);
                    success = !relapsed;
                }
            } else {
                // Completed anywhere in the window resolves immediately (even mid-window); otherwise
                // it only lapses once the whole window has elapsed.
                boolean completed = habitStructureRepository
                        .existsByHabitIdAndCompletedInWindow(
                                habit.getId(), Boolean.TRUE, anchor, windowLastDay);
                if (completed) {
                    success = true;
                } else if (windowClosed) {
                    success = false;
                } else {
                    success = null;
                }
            }

            if (success == null) {
                break; // current window is open and unresolved — stop here
            }
            if (success) {
                if (streak <= 0) {
                    pendingLastNeg = streak; // remember the negative depth for restoreNegativeStreak
                    clearLastNeg = false;
                    streak = 1;
                } else {
                    streak++;
                }
                if (streak > longestStreak) {
                    longestStreak = streak;
                }
            } else {
                if (streak > 0) {
                    streak = 0;
                    pendingLastNeg = null;
                    clearLastNeg = true;
                } else {
                    streak--;
                }
            }
            anchor = anchor.plusDays(freq);
        }

        Update update = new Update()
                .set("streak", streak)
                .set("longestStreak", longestStreak)
                .set("curDate", anchor);
        if (pendingLastNeg != null) {
            update.set("lastNegativeStreak", pendingLastNeg);
        } else if (clearLastNeg) {
            update.unset("lastNegativeStreak");
        }
        mongoTemplate.updateFirst(new Query(Criteria.where("id").is(habit.getId())), update, Habit.class);

        System.out.println("Habit #" + habit.getId() + " (" + habit.getName() + "): streak=" + streak
                + ", curDate=" + anchor + ", longest=" + longestStreak);
    }
}
