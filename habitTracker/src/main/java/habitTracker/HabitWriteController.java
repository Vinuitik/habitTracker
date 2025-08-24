package habitTracker;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitDTO;
import habitTracker.Habit.HabitService;
import habitTracker.Rules.RuleService;
import habitTracker.Rules.UpdateDTO;
import habitTracker.Structure.StructureService;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class HabitWriteController {
    
    private final HabitService habitService;
    private final StructureService structureService;
    private final RuleService ruleService;

    @DeleteMapping("/habits/delete/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Void> deleteHabit(@PathVariable Integer id) {
        try {
            habitService.deleteHabit(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            // Habit not found
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            // Unexpected error
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/new-habit")
    public String processHabitForm(@ModelAttribute Habit habit, BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("habit", habit);
            return "addHabitView/new-habit"; // Return to form with validation errors
        }
        habit.setCurDate(habit.getStartDate());
        habit.setActive(true);
        habit.setStreak(0);
        habit.setLongestStreak(0);
        habitService.saveHabit(habit);
        return "redirect:/habit";
    }

    @PostMapping("/habits/custom-add")
    @ResponseBody
    public String addHabitCustom(@RequestBody Habit habit) {
        habit.setCurDate(habit.getStartDate());
        habit.setActive(true);
        habit.setStreak(0);
        habit.setLongestStreak(0);
        habitService.saveHabit(habit);
        return "Habit added successfully";
    }

    @PostMapping("/habits/edit/{id}")
    @ResponseBody
    public String updateHabit(@PathVariable Integer id, 
                              @ModelAttribute Habit updatedHabit,
                              BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("habit", updatedHabit);
            return "editView/edit-habit"; // Return to the edit form with validation errors
        }

        // Update the habit in the database
        habitService.updateHabit(id, updatedHabit);
        //return "redirect:/habits/list"; // Redirect to the habits list after successful update
        return "Habit updated successfully";
    }
    @PostMapping("/habits/update/{habitId}")
    @ResponseBody
    public ResponseEntity<String> updateHabit(@PathVariable Integer habitId, 
                                              @RequestParam Boolean completed,
                                              @RequestParam(required = false) LocalDate date) {
        structureService.updateHabitCompletion(habitId, completed, date);
        return ResponseEntity.ok("Habit completion updated successfully");
    }
    @PostMapping("/habits/info/save")
    @ResponseBody
    public ResponseEntity<String> saveHabit(@RequestBody Habit habit) {
        try {
            Habit existingHabit = habitService.getHabitById(habit.getId());
            
            if (existingHabit != null) {
                if (habit.getName() != null && !habit.getName().isEmpty()) {
                    existingHabit.setName(habit.getName());
                }
                if (habit.getDescription() != null && !habit.getDescription().isEmpty()) {
                    existingHabit.setDescription(habit.getDescription());
                }
                if (habit.getTwoMinuteRule() != null && !habit.getTwoMinuteRule().isEmpty()) {
                    existingHabit.setTwoMinuteRule(habit.getTwoMinuteRule());
                }
                if (habit.getStatus() != null && !habit.getStatus().isEmpty()) {
                    existingHabit.setStatus(habit.getStatus());
                }
                if (habit.getStreak() != null) {
                    existingHabit.setStreak(habit.getStreak());
                }
                
                habitService.saveHabit(existingHabit);
                return ResponseEntity.ok("Habit saved successfully");
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Habit not found");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error saving habit");
        }
    }

    @PostMapping("/habits/addRule")
    @ResponseBody
    public ResponseEntity<String> addRule(@RequestBody UpdateDTO updateDTO) {
        try {
            ruleService.addRule(updateDTO.getMainId(), updateDTO.getSubIds());
            habitService.updateRule(updateDTO);
            return ResponseEntity.ok("Rule added successfully");
        } catch (Exception e) {
            System.err.println("Error adding rule: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error adding rule");
        }
    }

}
