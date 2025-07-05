package habitTracker.Structure;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitService;
import habitTracker.Rules.Rule;
import habitTracker.Rules.RuleService;
import habitTracker.Structure.StructureDTO.HabitStatus;
import habitTracker.util.Pair;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final HabitStructureRepository habitStructureRepository;
    private final HabitService habitService;
    private final RuleService ruleService;

    @Transactional(readOnly = true)
    public StructureDTO getTodayStructure() {
        LocalDate today = LocalDate.now();
        StructureDTO structure = getStructureForDate(today);
        return structure;
    }

    private StructureDTO getStructureForDate(LocalDate date) {
        List<HabitStructure> habitStructures = habitStructureRepository.findByStructureDate(date);
        List<Integer> habitIds = habitStructures.stream()
            .map(HabitStructure::getHabitId)
            .distinct()
            .collect(Collectors.toList());
        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        Map<Integer, Boolean> habitIdToActive = habits.stream()
            .collect(Collectors.toMap(Habit::getId, Habit::getActive));

        habitStructures = habitStructures.stream()
            .filter(habitStructure -> habitIdToActive.getOrDefault(habitStructure.getHabitId(), false))
            .collect(Collectors.toList());
        Map<Integer, String> habitIdToNameMap = getHabitIdToNameMap(habitStructures);
        return populateStructureDTO(date, habitStructures, habitIdToNameMap);
    }
    
    /**
     * Determines if a habit should be active/tracked on a specific date
     * Based on habit's startDate, endDate, frequency, and active status
     */
    private boolean isHabitActiveOnDate(Habit habit, LocalDate date) {
        // Check if habit is globally active
        if (habit.getActive() == null || !habit.getActive()) {
            return false;
        }
        
        // Check if date is before habit start date
        if (habit.getStartDate() != null && date.isBefore(habit.getStartDate())) {
            return false;
        }
        
        // Check if date is after habit end date
        if (habit.getEndDate() != null && date.isAfter(habit.getEndDate())) {
            return false;
        }
        
        // For daily habits (frequency=1), always active if within date range
        if (habit.getFrequency() == null || habit.getFrequency() == 1) {
            return true;
        }
        
        // For other frequencies, check if this is a scheduled day
        if (habit.getStartDate() != null) {
            long daysSinceStart = ChronoUnit.DAYS.between(habit.getStartDate(), date);
            return daysSinceStart >= 0 && daysSinceStart % habit.getFrequency() == 0;
        }
        
        return true; // Default to active if no specific frequency logic applies
    }

    private Map<Integer, String> getHabitIdToNameMap(List<HabitStructure> habitStructures) {
        List<Integer> habitIds = habitStructures.stream()
            .map(HabitStructure::getHabitId)
            .distinct()
            .collect(Collectors.toList());

        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        return habits.stream()
            .collect(Collectors.toMap(Habit::getId, Habit::getName));
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

    private StructureDTO populateStructureDTO(LocalDate date, List<HabitStructure> habitStructures, Map<Integer, String> habitIdToNameMap) {
        StructureDTO structure = new StructureDTO();
        structure.setDate(date);
        structure.setHabits(new HashMap<>());

        for (HabitStructure habitStructure : habitStructures) {
            String habitName = habitIdToNameMap.get(habitStructure.getHabitId());
            if (habitName != null) {
                Pair<String, Integer> habitKey = new Pair<>(habitName, habitStructure.getHabitId());
                structure.getHabits().put(habitKey, habitStructure.getCompleted());
            }
        }

        // Sort the habits by habit name to maintain consistent order
        structure.setHabits(structure.getHabits().entrySet().stream()
            .sorted((entry1, entry2) -> entry1.getKey().getKey().compareTo(entry2.getKey().getKey())) // Sort by habit name
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new // Maintain sorted order
            )));

        return structure;
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
        List<HabitStructure> habitStructures = habitStructureRepository.findByStructureDateBetween(startDate.minusDays(1), endDate.plusDays(1));
        return habitStructures;
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
        habitStructureRepository.save(habitStructure);

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
}
