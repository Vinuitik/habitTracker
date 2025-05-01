package habitTracker;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class HabitWriteController {
    
    private final HabitService habitService;

    @DeleteMapping("/habits/delete/{name}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Void> deleteHabit(@PathVariable String name) {
        try {
            habitService.deleteHabit(name);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            // Habit not found
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            // Unexpected error
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping({"/habits/update"})
    @ResponseBody
    public String changeHabitStatus(@RequestBody HabitDTO habitDTO) {
        LocalDate date = habitDTO.getDate();
        String name = habitDTO.getName();
        boolean status = habitDTO.isStatus();

        return "index";
    }

    @PostMapping("/new-habit")
    public String processHabitForm(@ModelAttribute Habit habit, BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("habit", habit);
            return "addHabitView/new-habit"; // Return to form with validation errors
        }
        habit.setCurDate(habit.getStartDate());
        habitService.saveHabit(habit);
        return "redirect:/habit";
    }

    @PostMapping("/habits/custom-add")
    @ResponseBody
    public String addHabitCustom(@RequestBody Habit habit) {
        habit.setCurDate(habit.getStartDate());
        habitService.saveHabit(habit);
        return "Habit added successfully";
    }

    @PostMapping("/habits/edit/{name}")
    public String updateHabit(@PathVariable String name, 
                            @ModelAttribute Habit updatedHabit,
                            BindingResult result) {
        if (result.hasErrors()) {
            return "edit-habit";
        }
        
        habitService.updateHabit(name, updatedHabit);
        return "redirect:/habits/list";
    }
}
