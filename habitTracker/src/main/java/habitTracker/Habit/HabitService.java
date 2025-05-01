package habitTracker.Habit;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HabitService {

    private final HabitRepository habitRepository;

    public HabitService(HabitRepository habitRepository) {
        this.habitRepository = habitRepository;
    }

    public void saveHabit(Habit habit) {
        habitRepository.save(habit);
    }
    public List<Habit> getAllHabits() {
        return habitRepository.findAll();
    }

    public List<String> getAllUniqueHabitNames() {
        return getAllHabits().stream()
                           .map(Habit::getName)
                           .distinct()
                           .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Habit> getHabitsByDate(LocalDate date) {
        return habitRepository.findByCurDate(date);
    }

    @Transactional
    public void deleteHabit(String name) {
        if (!habitRepository.existsById(name)) {
            throw new IllegalArgumentException("Habit not found: " + name);
        }
        habitRepository.deleteById(name);
    }

    @Transactional(readOnly = true)
    public Habit getHabitByName(String name) {
        return habitRepository.findById(name).orElse(null);
    }


    @Transactional
    public void updateHabit(String name, Habit updatedHabit) {
        Habit existingHabit = getHabitByName(name);
        if (existingHabit == null) {
            throw new IllegalArgumentException("Habit not found: " + name);
        }
        
        existingHabit.setFrequency(updatedHabit.getFrequency());
        habitRepository.save(existingHabit);
    }
}
