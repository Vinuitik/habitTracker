package habitTracker.Habit;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import habitTracker.auth.SecurityUtils;
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

    /**
     * Ownership guard for by-id access. Habits are addressed by small sequential-ish Integer ids,
     * so without this any authenticated user could read/modify another user's habit by guessing ids
     * (IDOR). getAllHabits()/findByUserId already scope list queries; this covers the by-id paths.
     */
    private boolean ownedByCurrentUser(Habit habit) {
        String userId = SecurityUtils.getCurrentUserId();
        return habit != null && userId != null && userId.equals(habit.getUserId());
    }

    public void saveHabit(Habit habit) {
        if (habit.getDefaultMade() == null) {
            habit.setDefaultMade(false);
        }
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) throw new IllegalStateException("Cannot save habit: no authenticated user");
        habit.setUserId(userId);
        habitRepository.save(habit);
        habitStructureRepository.save(HabitStructure.builder()
            .habitId(habit.getId())
            .structureDate(habit.getStartDate())
            .completed(false)
            .userId(habit.getUserId())
            .build());
    }

    public List<Habit> getAllHabits() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId != null) return habitRepository.findByUserId(userId);
        return List.of();
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
    
    @Transactional
    public void deleteHabit(Integer id) {
        Habit habit = habitRepository.findById(id).orElse(null);
        if (!ownedByCurrentUser(habit)) {
            throw new IllegalArgumentException("Habit not found with ID: " + id);
        }
        habit.setActive(false); // Mark as inactive instead of deleting
        habitRepository.save(habit);
    }

    @Transactional(readOnly = true)
    public List<Habit> getHabitByName(String name) {
        return habitRepository.findByName(name);
    }


    @Transactional
    public void updateHabit(Integer id, Habit updatedHabit) {
        Habit existingHabit = habitRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Habit not found with ID: " + id));
        if (!ownedByCurrentUser(existingHabit)) {
            throw new IllegalArgumentException("Habit not found with ID: " + id);
        }

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
        if (updatedHabit.getDefaultMade() != null) {
            existingHabit.setDefaultMade(updatedHabit.getDefaultMade());
        }
    
        Boolean isActive = updatedHabit.getActive();
        if(isActive == null){
            isActive = false;
        }
        Integer maxStreak = existingHabit.getStreak() != null ? existingHabit.getStreak() : 0;
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
                    .userId(existingHabit.getUserId())
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
        Habit habit = habitRepository.findById(id).orElse(null);
        return ownedByCurrentUser(habit) ? habit : null;
    }

    public List<Habit> getHabitsByIds(List<Integer> ids) {
        return habitRepository.findAllById(ids);
    }

    // Narrows an arbitrary id list down to the ones the current user actually owns.
    // Used before writing anything (e.g. Rule rows) keyed by caller-supplied habit ids,
    // so a request can't plant records pointing at another user's habit (IDOR).
    public List<Integer> filterOwnedHabitIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        return habitRepository.findAllById(ids).stream()
            .filter(this::ownedByCurrentUser)
            .map(Habit::getId)
            .collect(Collectors.toList());
    }

    public List<HabitDTO> getAllHabitsAsDTOs() {
        return getAllHabits().stream()
            .map(HabitDTO::fromHabit)
            .collect(Collectors.toList());
    }
    public List<HabitDTO> getAllActiveHabitsAsDTOs() {
        return getAllHabits().stream()
            .filter(habit -> (habit.getActive() != null && habit.getActive() != false))
            .map(HabitDTO::fromHabit)
            .collect(Collectors.toList());
    }
    public List<HabitDTO> getAllInactiveHabitsAsDTOs() {
        return getAllHabits().stream()
            .filter(habit -> (habit.getActive() == null || habit.getActive() == false))
            .map(HabitDTO::fromHabit)
            .collect(Collectors.toList());
    }

    public HabitDTO getHabitDTOById(Integer id) {
        Habit habit = getHabitById(id); // ownership-scoped
        return habit != null ? HabitDTO.fromHabit(habit) : null;
    }
    public List<StreakDTO> getStreaks(List<Integer> ids){
        List<Habit> habits = habitRepository.findAllById(ids);
        return habits.stream()
            .filter(this::ownedByCurrentUser) // never reveal another user's streaks
            .map(habit -> new StreakDTO(
                    habit.getId(),
                    habit.getStreak() != null ? habit.getStreak() : 0,
                    habit.getFrequency() != null && habit.getFrequency() > 0 ? habit.getFrequency() : 1))
            .collect(Collectors.toList());
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
    public void restoreNegativeStreak(Integer habitId) {
        Habit habit = habitRepository.findById(habitId).orElse(null);
        if (habit != null && habit.getLastNegativeStreak() != null) {
            habit.setStreak(habit.getLastNegativeStreak());
            habit.setLastNegativeStreak(null);
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
        Habit mainHabit = habitRepository.findById(updateDTO.getMainId())
            .orElseThrow(() -> new IllegalArgumentException("Main habit not found with ID: " + updateDTO.getMainId()));
        if (!ownedByCurrentUser(mainHabit)) {
            throw new IllegalArgumentException("Main habit not found with ID: " + updateDTO.getMainId());
        }
        // Only operate on sub-habits the caller actually owns.
        List<Habit> subHabits = habitRepository.findAllById(updateDTO.getSubIds()).stream()
            .filter(this::ownedByCurrentUser)
            .collect(Collectors.toList());
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
            .userId(mainHabit.getUserId())
            .build());
        
    }
}
