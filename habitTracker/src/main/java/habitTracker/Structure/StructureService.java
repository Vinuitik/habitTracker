package habitTracker.Structure;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Habit.Habit;
import habitTracker.auth.SecurityUtils;
import habitTracker.Habit.HabitService;
import habitTracker.Rules.Rule;
import habitTracker.Rules.RuleService;
import habitTracker.updater.HabitDateCalculator;
import habitTracker.Structure.StructureDTO.HabitStatus;
import habitTracker.util.Pair;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final HabitStructureRepository habitStructureRepository;
    private final HabitService habitService;
    private final RuleService ruleService;
    private final HabitDateCalculator habitDateCalculator;

    @Transactional(readOnly = true)
    public StructureDTO getTodayStructure() {
        LocalDate today = LocalDate.now();
        StructureDTO structure = getStructureForDate(today);
        structure = filterFailedNegativeHabits(structure, today);
        return structure;
    }

    // Habit/window-driven: a habit belongs on the given day when that day falls inside its current
    // grace window [curDate, curDate + frequency). Completion is resolved across the whole window,
    // not just the single day, so a long-period habit stays visible (and catchable) for its whole period.
    private StructureDTO getStructureForDate(LocalDate date) {
        StructureDTO structure = new StructureDTO();
        structure.setDate(date);
        structure.setHabits(new HashMap<>());
        structure.setHabitDetails(new HashMap<>());

        for (Habit habit : habitService.getAllHabits()) {
            if (!Boolean.TRUE.equals(habit.getActive()) || habit.getName() == null) {
                continue;
            }
            LocalDate anchor = habit.getCurDate();
            if (anchor == null) {
                continue;
            }
            int freq = habit.getFrequency() != null && habit.getFrequency() > 0 ? habit.getFrequency() : 1;
            boolean windowCoversDate = !date.isBefore(anchor) && date.isBefore(anchor.plusDays(freq));
            boolean withinEndDate = habit.getEndDate() == null || !date.isAfter(habit.getEndDate());
            if (!windowCoversDate || !withinEndDate) {
                continue;
            }

            Pair<String, Integer> habitKey = new Pair<>(habit.getName(), habit.getId());
            structure.getHabits().put(habitKey, isOccurrenceComplete(habit, anchor, freq));
            structure.getHabitDetails().put(habitKey, habit);
        }

        // Sort by habit name for a stable order.
        structure.setHabits(structure.getHabits().entrySet().stream()
            .sorted((e1, e2) -> e1.getKey().getKey().compareTo(e2.getKey().getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)));
        return structure;
    }

    // Mirrors the resolution used by the daily engine (HabitUpdateService): a defaultMade habit is
    // "done" for the window unless there is a relapse in it; a normal habit is "done" when it was
    // completed anywhere in the window.
    private boolean isOccurrenceComplete(Habit habit, LocalDate anchor, int freq) {
        LocalDate windowLastDay = anchor.plusDays(freq - 1);
        if (Boolean.TRUE.equals(habit.getDefaultMade())) {
            return !habitStructureRepository.existsByHabitIdAndCompletedInWindow(
                    habit.getId(), Boolean.FALSE, anchor, windowLastDay);
        }
        return habitStructureRepository.existsByHabitIdAndCompletedInWindow(
                habit.getId(), Boolean.TRUE, anchor, windowLastDay);
    }

    private boolean isHabitActiveOnDate(Habit habit, LocalDate date) {
        if (habit.getActive() == null || !habit.getActive()) {
            return false;
        }
        return habitDateCalculator.shouldTrackHabitOnDate(habit, date);
    }

    private Map<Integer, String> getHabitIdToNameMapFromIds(List<Pair<String, Integer>> habitNames) {
        List<Integer> habitIds = habitNames.stream()
            .map(Pair::getValue)
            .distinct()
            .collect(Collectors.toList());

        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        return habits.stream()
            .collect(Collectors.toMap(Habit::getId, Habit::getName));
    }

    @Transactional(readOnly = true)
    public List<StructureDTO> getStructuresForDateRange(LocalDate startDate, LocalDate endDate, List<Pair<String, Integer>> habitNames) {
        Map<LocalDate, StructureDTO> structureMap = initializeStructureMap(startDate, endDate);
        List<HabitStructure> habitStructures = fetchHabitStructures(startDate, endDate);
        Map<Integer, String> habitIdToNameMap = getHabitIdToNameMapFromIds(habitNames);
        populateStructureMap(structureMap, habitStructures, habitIdToNameMap);
        fillMissingHabits(structureMap, habitNames);
        
        // Enhanced: Populate habit activity statuses
        populateHabitStatuses(structureMap, habitNames, startDate, endDate);

        return structureMap.values().stream()
            .sorted((s1, s2) -> s1.getDate().compareTo(s2.getDate()))
            .collect(Collectors.toList());
    }

    private Map<LocalDate, StructureDTO> initializeStructureMap(LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, StructureDTO> structureMap = new HashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            StructureDTO structure = new StructureDTO();
            structure.setDate(date);
            structure.setHabits(new HashMap<>());
            structure.setHabitStatuses(new HashMap<>()); // Initialize habit statuses map
            structureMap.put(date, structure);
        }
        return structureMap;
    }

    private List<HabitStructure> fetchHabitStructures(LocalDate startDate, LocalDate endDate) {
        String userId = SecurityUtils.getCurrentUserId();
        return userId != null
                ? habitStructureRepository.findByStructureDateBetweenAndUserId(startDate.minusDays(1), endDate.plusDays(1), userId)
                : List.of();
    }

    private void populateStructureMap(Map<LocalDate, StructureDTO> structureMap, List<HabitStructure> habitStructures, Map<Integer, String> habitIdToNameMap) {
        for (HabitStructure habitStructure : habitStructures) {
            if(habitStructure.getStructureDate() == null) {
                continue; // Skip if structure date is null
            }
            LocalDate storedDate = habitStructure.getStructureDate();
            StructureDTO structure = structureMap.get(storedDate);

            String habitName = habitIdToNameMap.get(habitStructure.getHabitId());
            if (habitName != null) {
                Pair<String, Integer> habitKey = new Pair<>(habitName, habitStructure.getHabitId());
                structure.getHabits().put(habitKey, habitStructure.getCompleted());
            }
        }

        // Sort the habits in each StructureDTO by habit name
        for (StructureDTO structure : structureMap.values()) {
            structure.setHabits(structure.getHabits().entrySet().stream()
                .sorted((entry1, entry2) -> entry1.getKey().getKey().compareTo(entry2.getKey().getKey())) // Sort by habit name
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new // Maintain sorted order
                )));
        }
    }

    private void fillMissingHabits(Map<LocalDate, StructureDTO> structureMap, List<Pair<String, Integer>> habitNames) {
        for (StructureDTO dto : structureMap.values()) {
            for (Pair<String, Integer> habitName : habitNames) {
                if (!dto.getHabits().containsKey(habitName)) {
                    dto.getHabits().put(habitName, false); // Default to false if not present
                }
            }

            // Sort the habits after adding missing ones by habit name
            dto.setHabits(dto.getHabits().entrySet().stream()
                .sorted((entry1, entry2) -> entry1.getKey().getKey().compareTo(entry2.getKey().getKey())) // Sort by habit name
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new // Maintain sorted order
                )));
        }
    }

    /**
     * Populates habit statuses for each date based on habit activity and completion
     */
    private void populateHabitStatuses(Map<LocalDate, StructureDTO> structureMap, 
                                     List<Pair<String, Integer>> habitNames, 
                                     LocalDate startDate, LocalDate endDate) {
        // Get all habits
        List<Integer> habitIds = habitNames.stream()
            .map(Pair::getValue)
            .collect(Collectors.toList());
        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        Map<Integer, Habit> habitMap = habits.stream()
            .collect(Collectors.toMap(Habit::getId, habit -> habit));
        
        // For each date and habit, determine the status
        for (StructureDTO structure : structureMap.values()) {
            LocalDate date = structure.getDate();
            
            for (Pair<String, Integer> habitPair : habitNames) {
                Integer habitId = habitPair.getValue();
                Habit habit = habitMap.get(habitId);
                
                if (habit == null) {
                    continue; // Skip if habit not found
                }
                
                HabitStatus status;
                if (isHabitActiveOnDate(habit, date)) {
                    // Habit is active on this date
                    Boolean completed = structure.getHabits().get(habitPair);
                    if (completed != null && completed) {
                        status = HabitStatus.ACTIVE_COMPLETED;
                    } else {
                        status = HabitStatus.ACTIVE_INCOMPLETE;
                    }
                } else {
                    // Habit is not active on this date
                    status = HabitStatus.INACTIVE;
                }
                
                structure.getHabitStatuses().put(habitPair, status);
            }
            
            // Sort habit statuses by habit name for consistency
            structure.setHabitStatuses(structure.getHabitStatuses().entrySet().stream()
                .sorted((entry1, entry2) -> entry1.getKey().getKey().compareTo(entry2.getKey().getKey()))
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                )));
        }
    }

    @Transactional
    public void updateHabitCompletion(Integer habitId, Boolean completed, LocalDate date) {
        // Ownership guard: getHabitById is scoped to the current user, so this rejects
        // attempts to toggle a habit the caller doesn't own (IDOR on /habits/update/{id}).
        if (habitService.getHabitById(habitId) == null) {
            throw new IllegalArgumentException("Habit not found with ID: " + habitId);
        }
        if(date == null) {
            date = LocalDate.now();
        }
        HabitStructure habitStructure = habitStructureRepository.findByHabitIdAndStructureDate(habitId, date)
            .orElse(null);
        if (habitStructure == null) {
            habitStructure = new HabitStructure();
            habitStructure.setHabitId(habitId);
            habitStructure.setStructureDate(date);
        }
        habitStructure.setCompleted(completed);
        // Without this, toggles written here have no userId while cron-created records do —
        // any userId-scoped read (e.g. getStructuresForDateRange) silently misses them on reload.
        habitStructure.setUserId(SecurityUtils.getCurrentUserId());
        habitStructureRepository.save(habitStructure);

        if (Boolean.FALSE.equals(completed) && LocalDate.now().equals(date)) {
            habitService.restoreNegativeStreak(habitId);
        }

        List<Rule> rules = ruleService.getRulesByMainId(habitId);
        List<Integer> subIds = rules.stream()
            .map(Rule::getHabitSubId)
            .collect(Collectors.toList());
        System.out.println(habitId);
        System.out.println("Sub IDs: " + subIds);
        for(Integer subId : subIds) {
            if(subId == null || subId.equals(habitId)) {
                continue; // Skip if subId is null, or if it is the same as habitId in order to prevent infinite iteration
            }
            updateHabitCompletion(subId, completed, date);
        }
    }

    /**
     * Filters out negative habits (defaultMade=true) that have been failed (completed=false) today
     * This prevents them from reappearing on refresh after being shamefully removed
     */
    private StructureDTO filterFailedNegativeHabits(StructureDTO structure, LocalDate date) {
        if (structure == null || structure.getHabits() == null || structure.getHabits().isEmpty()) {
            return structure;
        }

        // Get all habit IDs from the current structure
        List<Integer> habitIds = structure.getHabits().keySet().stream()
            .map(Pair::getValue)
            .collect(Collectors.toList());

        // Fetch full habit objects to check defaultMade property
        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        Map<Integer, Habit> habitMap = habits.stream()
            .collect(Collectors.toMap(Habit::getId, habit -> habit));

        // Filter out failed negative habits
        Map<Pair<String, Integer>, Boolean> filteredHabits = structure.getHabits().entrySet().stream()
            .filter(entry -> {
                Integer habitId = entry.getKey().getValue();
                Boolean completed = entry.getValue();
                Habit habit = habitMap.get(habitId);
                
                // Keep habit if it's not a negative habit, or if it's completed, or if habit data is missing
                if (habit == null || habit.getDefaultMade() == null || !habit.getDefaultMade()) {
                    return true; // Keep non-negative habits
                }
                
                // For negative habits (defaultMade=true), only keep them if they're completed (not failed)
                return completed != null && completed;
            })
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new // Maintain order
            ));

        // Also filter habitDetails to match
        if (structure.getHabitDetails() != null) {
            Map<Pair<String, Integer>, Habit> filteredHabitDetails = structure.getHabitDetails().entrySet().stream()
                .filter(entry -> filteredHabits.containsKey(entry.getKey()))
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ));
            structure.setHabitDetails(filteredHabitDetails);
        }

        structure.setHabits(filteredHabits);
        return structure;
    }
}
