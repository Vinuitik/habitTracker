package habitTracker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitService;
import habitTracker.Rules.RuleService;
import habitTracker.Rules.UpdateDTO;
import habitTracker.Structure.StructureService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HabitWriteController {

    private final HabitService habitService;
    private final StructureService structureService;
    private final RuleService ruleService;

    @DeleteMapping("/habits/delete/{id}")
    @Transactional
    public ResponseEntity<Void> deleteHabit(@PathVariable Integer id) {
        try {
            habitService.deleteHabit(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/new-habit")
    public ResponseEntity<String> processHabitForm(@RequestBody Habit habit) {
        habit.setCurDate(habit.getStartDate());
        habit.setActive(true);
        habit.setStreak(0);
        habit.setLongestStreak(0);
        habitService.saveHabit(habit);
        return ResponseEntity.ok("Habit added successfully");
    }

    @PostMapping("/habits/edit/{id}")
    public ResponseEntity<String> updateHabit(@PathVariable Integer id, @RequestBody Habit updatedHabit) {
        habitService.updateHabit(id, updatedHabit);
        return ResponseEntity.ok("Habit updated successfully");
    }

    @PostMapping("/habits/update/{habitId}")
    public ResponseEntity<String> updateCompletion(@PathVariable Integer habitId,
                                                   @RequestParam Boolean completed,
                                                   @RequestParam(required = false) LocalDate date) {
        structureService.updateHabitCompletion(habitId, completed, date);
        return ResponseEntity.ok("Habit completion updated successfully");
    }

    @PostMapping("/habits/info/save")
    public ResponseEntity<String> saveHabit(@RequestBody Habit habit) {
        try {
            Habit existing = habitService.getHabitById(habit.getId());
            if (existing == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Habit not found");
            if (habit.getName() != null && !habit.getName().isEmpty()) existing.setName(habit.getName());
            if (habit.getDescription() != null && !habit.getDescription().isEmpty()) existing.setDescription(habit.getDescription());
            if (habit.getTwoMinuteRule() != null && !habit.getTwoMinuteRule().isEmpty()) existing.setTwoMinuteRule(habit.getTwoMinuteRule());
            if (habit.getStatus() != null && !habit.getStatus().isEmpty()) existing.setStatus(habit.getStatus());
            if (habit.getStreak() != null) existing.setStreak(habit.getStreak());
            if (habit.getDefaultMade() != null) existing.setDefaultMade(habit.getDefaultMade());
            habitService.saveHabit(existing);
            return ResponseEntity.ok("Habit saved successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error saving habit");
        }
    }

    @PostMapping("/habits/addRule")
    public ResponseEntity<String> addRule(@RequestBody UpdateDTO updateDTO) {
        try {
            ruleService.addRule(updateDTO.getMainId(), updateDTO.getSubIds());
            habitService.updateRule(updateDTO);
            return ResponseEntity.ok("Rule added successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error adding rule");
        }
    }
}
