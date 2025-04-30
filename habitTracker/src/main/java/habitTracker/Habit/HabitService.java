package habitTracker.Habit;

import org.springframework.stereotype.Service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

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
}
