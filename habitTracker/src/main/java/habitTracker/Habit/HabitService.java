package habitTracker.Habit;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.annotation.Transactional;

import habitTracker.Rules.RuleService;
import habitTracker.Rules.UpdateDTO;
import habitTracker.Structure.HabitStructure;
import habitTracker.Structure.HabitStructureRepository;
import habitTracker.util.Pair;

@Service
public class HabitService {

    private final HabitRepository habitRepository;
    private final HabitStructureRepository habitStructureRepository;
    private final RuleService ruleService;

    public HabitService(HabitRepository habitRepository, HabitStructureRepository habitStructureRepository, RuleService ruleService) {
        this.ruleService = ruleService;
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
        Integer maxStreak = 0;
        if(!isActive) {
            habitStructureRepository.deleteByHabitIdAndStructureDate(
                existingHabit.getId(), LocalDate.now());
        } else if( isActive && existingHabit.getActive() != true ) {
            List<Integer> mainIds = ruleService.getMainIdsBySubId(existingHabit.getId());
            List<Habit> mainHabits = habitRepository.findAllById(mainIds);
            for (Habit mainHabit : mainHabits) {
                if (mainHabit.getStreak() != null && mainHabit.getStreak() > maxStreak) {
                    maxStreak = mainHabit.getStreak();
                }
            }
            boolean exists = habitStructureRepository.existsByHabitIdAndStructureDate(
                existingHabit.getId(), LocalDate.now());
            if (!exists) {
                habitStructureRepository.save(HabitStructure.builder()
                    .habitId(existingHabit.getId())
                    .structureDate(LocalDate.now())
                    .completed(false)
                    .build());
            }
            ruleService.deleteBySubId(existingHabit.getId());
        }
        existingHabit.setActive(isActive); // Update active status
        existingHabit.setStreak(maxStreak);

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

    public void updateHabitFrequency(List<Integer> ids, Integer frequency) {
        List<Habit> habits = habitRepository.findAllById(ids);
        for (Habit habit : habits) {
            habit.setFrequency(frequency);
            habitRepository.save(habit);
        }
    }

    public void updateActiveStatus(List<Integer> ids, Boolean active) {
        List<Habit> habits = habitRepository.findAllById(ids);
        for (Habit habit : habits) {
            habit.setActive(active);
            habitRepository.save(habit);
        }
    }
    public void updateHabitStreak(List<Integer> ids, Integer streak) {
        List<Habit> habits = habitRepository.findAllById(ids);
        for (Habit habit : habits) {
            habit.setStreak(streak);
            habitRepository.save(habit);
        }
    }

    public void updateRule(UpdateDTO updateDTO){
        List<Habit> subHabits = habitRepository.findAllById(updateDTO.getSubIds());
        Habit mainHabit = habitRepository.findById(updateDTO.getMainId())
            .orElseThrow(() -> new IllegalArgumentException("Main habit not found with ID: " + updateDTO.getMainId()));
        for(Habit subHabit : subHabits) {
            subHabit.setActive(false);
            subHabit.setFrequency(updateDTO.getFrequency());
            subHabit.setStreak(updateDTO.getStreak());
            habitRepository.save(subHabit);
            habitStructureRepository.deleteByHabitIdAndStructureDate(
                subHabit.getId(), LocalDate.now());
        }
        mainHabit.setActive(true);
        mainHabit.setFrequency(updateDTO.getFrequency());
        mainHabit.setStreak(updateDTO.getStreak());

        habitRepository.save(mainHabit);
        habitStructureRepository.save(HabitStructure.builder()
            .habitId(mainHabit.getId())
            .structureDate(LocalDate.now())
            .completed(false)
            .build());
        
    }
}
