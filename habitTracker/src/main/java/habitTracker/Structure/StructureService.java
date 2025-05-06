package habitTracker.Structure;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitService;
import habitTracker.util.Pair;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final HabitStructureRepository habitStructureRepository;
    private final HabitService habitService;

    @Transactional(readOnly = true)
    public StructureDTO getTodayStructure() {
        // Convert today's date into UTC
        LocalDate today = LocalDate.now(java.time.Clock.systemUTC());

        // Step 1: Fetch all HabitStructures for today's date
        List<HabitStructure> habitStructures = habitStructureRepository.findByStructureDate(today);

        // Step 2: Collect all habit IDs to resolve their names in a single query
        List<Integer> habitIds = habitStructures.stream()
            .map(HabitStructure::getHabitId)
            .distinct()
            .collect(Collectors.toList());
        

        // Step 3: Fetch all habits by their IDs in one query
        List<Habit> habits = habitService.getHabitsByIds(habitIds);
        Map<Integer, String> habitIdToNameMap = habits.stream()
            .collect(Collectors.toMap(Habit::getId, Habit::getName));


        // Step 4: Populate the StructureDTO with HabitStructure data
        StructureDTO structure = new StructureDTO();
        structure.setDate(today);
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
    public List<StructureDTO> getStructuresForDateRange(LocalDate startDate, LocalDate endDate, List<Pair<String, Integer> > habitNames) {

        // Convert startDate and endDate to UTC
        startDate = startDate.atStartOfDay(java.time.Clock.systemUTC().getZone()).toLocalDate();
        endDate = endDate.atStartOfDay(java.time.Clock.systemUTC().getZone()).toLocalDate();

        // Step 1: Initialize the structure map for the date range
        Map<LocalDate, StructureDTO> structureMap = new HashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            StructureDTO structure = new StructureDTO();
            structure.setDate(date);
            structure.setHabits(new HashMap<>());
            structureMap.put(date, structure);
        }

        // Step 2: Fetch all HabitStructures for the date range in one query
        List<HabitStructure> habitStructures = habitStructureRepository.findByStructureDateBetween(startDate, endDate);


        // Step 3: Collect all habit IDs to resolve their names in a single query
        List<Integer> habitIds = habitStructures.stream()
            .map(HabitStructure::getHabitId)
            .distinct()
            .collect(Collectors.toList());

        // Step 4: Fetch all habits by their IDs in one query
        Map<Integer, String> habitIdToNameMap = habitService.getHabitsByIds(habitIds).stream()
            .collect(Collectors.toMap(Habit::getId, Habit::getName));

        // Step 5: Populate the structure map with HabitStructure data
        for (HabitStructure habitStructure : habitStructures) {

            System.out.println("HabitStructure: " + habitStructure);

            LocalDate date = habitStructure.getStructureDate();
            StructureDTO structure = structureMap.get(date);

            String habitName = habitIdToNameMap.get(habitStructure.getHabitId());
            if (habitName != null) {
                Pair<String, Integer> habitKey = new Pair<>(habitName, habitStructure.getHabitId());
                structure.getHabits().put(habitKey, habitStructure.getCompleted());
            }
        }

        for(StructureDTO dto : structureMap.values()){
            for(Pair<String, Integer> habitName : habitNames) {
                if (!dto.getHabits().containsKey(habitName)) {
                    dto.getHabits().put(habitName, false); // Default to false if not present
                }
            }
        }

        // Step 6: Return the list of StructureDTOs
        return structureMap.values().stream()
            .sorted((s1, s2) -> s1.getDate().compareTo(s2.getDate()))
            .collect(Collectors.toList());
    }

    @Transactional
    public void updateHabitCompletion(Integer habitId, Boolean completed) {
        HabitStructure habitStructure = habitStructureRepository.findByHabitIdAndStructureDate(habitId, LocalDate.now())
            .orElseThrow(() -> new IllegalArgumentException("HabitStructure not found with ID: " + habitId));
        habitStructure.setCompleted(completed);
        habitStructureRepository.save(habitStructure);
    }
}
