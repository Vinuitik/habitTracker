package habitTracker;


import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitDTO;
import habitTracker.Habit.HabitService;
import habitTracker.Structure.Structure;
import habitTracker.Structure.StructureService;


@Controller
@RequiredArgsConstructor
public class HabitController {

    private final HabitService habitService;
    private final StructureService structureService;

    @GetMapping({"/","/habit"})
    public String getMethodName(Model model) {
        Structure structure = structureService.getTodayStructure();
        model.addAttribute("structure", structure); 
        return "index";
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

    @GetMapping("/habits/list")
    public String listHabits(Model model) {
        List<Habit> habits = habitService.getAllHabits();
        model.addAttribute("habits", habits);
        return "habits-list";  // This will look for habits-list.html in templates folder
    }
    
}
