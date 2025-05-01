package habitTracker.Structure;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitRepository;
import habitTracker.Habit.HabitService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final StructureRepository structureRepository;
    private final HabitRepository habitRepository;
    private final HabitService habitService;

    @Transactional(readOnly = true)
    public Structure getTodayStructure() {
        LocalDate today = LocalDate.now();
        List<Habit> habits = habitService.getHabitsByDate(today);  // Using service instead of direct repository
        Structure structure = new Structure();
        structure.setDate(today);
        structure.setHabits(new HashMap<>());
        for (Habit habit : habits) {
            structure.getHabits().put(habit.getName(), false);
        }
        return structure;
    }

    public List<TableViewDTO> getStructuresForDateRange(LocalDate startDate, LocalDate endDate) {
        List<Structure> structures = structureRepository.findByDateBetween(startDate, endDate);
        List<String> allHabits = habitService.getAllUniqueHabitNames();
        
        return startDate.datesUntil(endDate.plusDays(1))
            .map(date -> {
                TableViewDTO dto = new TableViewDTO();
                dto.setDate(date);
                
                // Find structure for this date or create empty one
                Structure structure = structures.stream()
                    .filter(s -> s.getDate().equals(date))
                    .findFirst()
                    .orElse(new Structure(date, new HashMap<>()));
                
                // Initialize all habits with false, then overlay actual values
                Map<String, Boolean> statusMap = new HashMap<>();
                allHabits.forEach(habit -> statusMap.put(habit, false));
                if (structure.getHabits() != null) {
                    statusMap.putAll(structure.getHabits());
                }
                
                dto.setHabitStatuses(statusMap);
                return dto;
            })
            .collect(Collectors.toList());
    }
}
