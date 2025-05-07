package habitTracker;


import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitDTO;
import habitTracker.Habit.HabitService;
import habitTracker.Structure.Structure;
import habitTracker.Structure.StructureDTO;
import habitTracker.Structure.StructureService;
import habitTracker.util.Pair;


@Controller
@RequiredArgsConstructor
public class HabitReadController {

    private final HabitService habitService;
    private final StructureService structureService;

    @GetMapping({"/","/habit"})
    public String getMethodName(Model model) {
        StructureDTO structure = structureService.getTodayStructure();
        structure.getHabits().forEach((key, value) -> {
            System.out.println("Habit: " + key + ", Details: " + value);
        });
        System.out.println("Structure: ");
        model.addAttribute("structure", structure); 
        return "index";
    }

    @GetMapping("/habits/list")
    public String listHabits(Model model) {
        List<HabitDTO> habits = habitService.getAllHabitsAsDTOs();
        model.addAttribute("habits", habits);
        return "habits-list"; // This will look for habits-list.html in templates folder
    }
    
    @GetMapping("/habits/table")
    public String getHabitTable(Model model, 
                              @RequestParam(required = false) LocalDate startDate,
                              @RequestParam(required = false) LocalDate endDate) {
        if (startDate == null) {
            startDate = LocalDate.now().minusDays(7);
        }
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        
        List<Pair<String,Integer> > habitNames = habitService.getAllUniqueHabitNamesIds();

        List<StructureDTO> tableData = structureService.getStructuresForDateRange(startDate, endDate, habitNames);

        
        model.addAttribute("habitNames", habitNames);
        model.addAttribute("tableData", tableData);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        
        return "habit-table";
    }

    @GetMapping("/habits/edit/{id}")
    public String showEditForm(@PathVariable Integer id, Model model) {
        Habit habit = habitService.getHabitById(id);
        if (habit == null) {
            return "error-page"; // Replace with the name of your error page template
        }
        model.addAttribute("habit", habit);
        return "edit-habit";
    }
    
}
