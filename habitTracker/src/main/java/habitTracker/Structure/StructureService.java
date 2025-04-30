package habitTracker.Structure;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final StructureRepository structureRepository;
    private final HabitRepository habitRepository;

    public Structure getTodayStructure(){
        LocalDate today = LocalDate.now();
        List<Habit> habits = habitRepository.findByCurDate(today);
        Structure structure = new Structure();
        structure.setDate(today);
        structure.setHabits(new java.util.HashMap<>());
        for (Habit habit : habits) {
            structure.getHabits().put(habit.getName(), false); // Initialize all habits to false (not completed)
        }
        return structure;
    }
}
