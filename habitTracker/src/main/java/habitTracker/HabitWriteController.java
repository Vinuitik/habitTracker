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

import org.springframework.http.HttpStatus;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitDTO;
import habitTracker.Habit.HabitService;
import habitTracker.Structure.StructureService;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class HabitWriteController {
    
    private final HabitService habitService;
    private final StructureService structureService;

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
        habitService.saveHabit(habit);
        return "redirect:/habit";
    }

    @PostMapping("/habits/custom-add")
    @ResponseBody
    public String addHabitCustom(@RequestBody Habit habit) {
        habit.setCurDate(habit.getStartDate());
        habit.setActive(true);
        habitService.saveHabit(habit);
        return "Habit added successfully";
    }

    @PostMapping("/habits/edit/{id}")
    public String updateHabit(@PathVariable Integer id, 
                              @ModelAttribute Habit updatedHabit,
                              BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("habit", updatedHabit);
            return "editView/edit-habit"; // Return to the edit form with validation errors
        }

        // Update the habit in the database
        habitService.updateHabit(id, updatedHabit);
        return "redirect:/habits/list"; // Redirect to the habits list after successful update
    }
    @PostMapping("/habits/update/{habitId}")
    @ResponseBody
    public ResponseEntity<String> updateHabit(@PathVariable Integer habitId, 
                                              @RequestParam Boolean completed) {
        structureService.updateHabitCompletion(habitId, completed);
        return ResponseEntity.ok("Habit completion updated successfully");
    }
}
