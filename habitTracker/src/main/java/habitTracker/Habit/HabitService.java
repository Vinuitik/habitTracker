package habitTracker.Habit;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Structure.HabitStructure;
import habitTracker.Structure.HabitStructureRepository;
import habitTracker.util.Pair;

@Service
public class HabitService {

    private final HabitRepository habitRepository;
    private final HabitStructureRepository habitStructureRepository;

    public HabitService(HabitRepository habitRepository, HabitStructureRepository habitStructureRepository) {
        this.habitRepository = habitRepository;
        this.habitStructureRepository = habitStructureRepository;
    }

    public void saveHabit(Habit habit) {
        habitRepository.save(habit);
        habitStructureRepository.save(HabitStructure.builder()
            .habitId(habit.getId())
            .structureDate(habit.getStartDate())
            .completed(false)
            .build());
    }
    public List<Habit> getAllHabits() {
        return habitRepository.findAll();
    }

    public List<Pair<String, Integer> > getAllUniqueHabitNamesIds() {
        return getAllHabits().stream()
                           .filter(habit-> (habit.getActive() != null && habit.getActive()!= false))
                           .map(habit -> new Pair<>(habit.getName(), habit.getId()))
                           .distinct()
                           .collect(Collectors.toList());
    }

    public List<Habit> getHabitsByDate(LocalDate date) {
        LocalDate utcDate = date.atStartOfDay().atZone(java.time.ZoneId.systemDefault())
                    .withZoneSameInstant(java.time.ZoneOffset.UTC)
                    .toLocalDate();
        return habitRepository.findByCurDate(utcDate);
    }
    
    @Transactional(readOnly = true)
    public void deleteHabit(Integer id) {
        Habit habit = habitRepository.findById(id).orElse(null);
        if (habit != null) {
            habit.setActive(false); // Mark as inactive instead of deleting
            habitRepository.save(habit);
        }
    }

    @Transactional(readOnly = true)
    public List<Habit> getHabitByName(String name) {
        return habitRepository.findByName(name);
    }


    @Transactional
    public void updateHabit(Integer id, Habit updatedHabit) {
        Habit existingHabit = habitRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Habit not found with ID: " + id));

        // Update the fields
        if(updatedHabit.getName() != null) {
            existingHabit.setName(updatedHabit.getName());
        }
        existingHabit.setEndDate(updatedHabit.getEndDate());// so that we can make it null again
        if(updatedHabit.getFrequency() != null) {
            existingHabit.setFrequency(updatedHabit.getFrequency()); // Update frequency if needed
        }
        if(updatedHabit.getStartDate() != null) {
            existingHabit.setStartDate(updatedHabit.getStartDate()); // Update start date if needed
        }
    
        Boolean isActive = updatedHabit.getActive();
        if(isActive == null){
            isActive = false;
        }
        if(!isActive) {
            habitStructureRepository.deleteByHabitIdAndStructureDate(
                existingHabit.getId(), LocalDate.now());
        } else if( isActive && existingHabit.getActive() != true ) {
            habitStructureRepository.save(  HabitStructure.builder()
                .habitId(existingHabit.getId())
                .structureDate(LocalDate.now())
                .completed(false)
                .build());
        }
        existingHabit.setActive(isActive); // Update active status

        // Save the updated habit
        habitRepository.save(existingHabit);
    }

    public Habit getHabitById(Integer id) {
        return habitRepository.findById(id).orElse(null);
    }

    public List<Habit> getHabitsByIds(List<Integer> ids) {
        return habitRepository.findAllById(ids);
    }

    public List<HabitDTO> getAllHabitsAsDTOs() {
        return habitRepository.findAll().stream()
            .map(HabitDTO::fromHabit) // Convert Habit to HabitDTO
            .collect(Collectors.toList());
    }
    public List<HabitDTO> getAllActiveHabitsAsDTOs() {
        return habitRepository.findAll().stream()
            .filter(habit -> (habit.getActive() != null && habit.getActive() != false))
            .map(HabitDTO::fromHabit) // Convert Habit to HabitDTO
            .collect(Collectors.toList());
    }
    public List<HabitDTO> getAllInactiveHabitsAsDTOs() {
        return habitRepository.findAll().stream()
            .filter(habit -> (habit.getActive() == null || habit.getActive() == false))
            .map(HabitDTO::fromHabit) // Convert Habit to HabitDTO
            .collect(Collectors.toList());
    }

    public HabitDTO getHabitDTOById(Integer id) {
        Habit habit = habitRepository.findById(id).orElse(null);
        return habit != null ? HabitDTO.fromHabit(habit) : null;
    }
    public List<Pair<Integer,Integer> > getStreaks(List<Integer> ids){
        List<Pair<Integer,Integer> > streaks;
        List<Habit> habits = habitRepository.findAllById(ids);
        streaks = habits.stream()
            .map(habit -> ( new Pair<Integer,Integer>(habit.getId(), habit.getStreak())))
            .collect(Collectors.toList());
        return streaks;
    }
}
