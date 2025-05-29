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
import habitTracker.Habit.HabitService;
import habitTracker.Rules.Rule;
import habitTracker.Rules.RuleService;
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
        return getStructureForDate(today);
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

        return structure;
    }

    @Transactional(readOnly = true)
    public List<StructureDTO> getStructuresForDateRange(LocalDate startDate, LocalDate endDate, List<Pair<String, Integer>> habitNames) {
        Map<LocalDate, StructureDTO> structureMap = initializeStructureMap(startDate, endDate);
        List<HabitStructure> habitStructures = fetchHabitStructures(startDate, endDate);
        Map<Integer, String> habitIdToNameMap = getHabitIdToNameMapFromIds(habitNames);
        populateStructureMap(structureMap, habitStructures, habitIdToNameMap);
        fillMissingHabits(structureMap, habitNames);

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

        // Sort the habits in each StructureDTO by habit ID
        for (StructureDTO structure : structureMap.values()) {
            structure.setHabits(structure.getHabits().entrySet().stream()
                .sorted((entry1, entry2) -> Integer.compare(entry1.getKey().getValue(), entry2.getKey().getValue())) // Sort by habit ID
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

            // Sort the habits after adding missing ones
            dto.setHabits(dto.getHabits().entrySet().stream()
                .sorted((entry1, entry2) -> Integer.compare(entry1.getKey().getValue(), entry2.getKey().getValue())) // Sort by habit ID
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new // Maintain sorted order
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
