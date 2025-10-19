package habitTracker;


import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

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
import habitTracker.Rules.RuleDTO;
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
    public String getMethodName(Model model, HttpServletResponse response) {
        // Prevent caching
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        
        StructureDTO structure = structureService.getTodayStructure();
        
        // Debug logging
        System.out.println("=== Structure Debug Info ===");
        System.out.println("Date: " + structure.getDate());
        System.out.println("Habits: " + structure.getHabits());
        System.out.println("Habit Statuses: " + structure.getHabitStatuses());
        System.out.println("Habit Details: " + structure.getHabitDetails());
        System.out.println("===========================");
        
        model.addAttribute("structure", structure); 
        return "index";
    }

    @GetMapping("/habits/list")
    public String listHabits(Model model) {
        List<HabitDTO> habits = habitService.getAllActiveHabitsAsDTOs();
        habits.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        model.addAttribute("habits", habits);
        return "habits-list"; // This will look for habits-list.html in templates folder
    }

    @GetMapping("/habits/add")
    public String showAddForm(Model model) {
        return "redirect:/addHabitView/new-habit.html"; // This will look for add-habit.html in templates folder
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
        habitNames.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));

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

    @GetMapping(value = "/habits/tableAsync", produces = "application/json")
    @ResponseBody
    public List<StructureDTO> getHabitTableData(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
    if (startDate == null) {
        startDate = LocalDate.now().minusDays(7);
    }
    if (endDate == null) {
        endDate = LocalDate.now();
    }

        List<Pair<String, Integer>> habitNames = habitService.getAllUniqueHabitNamesIds();
        habitNames.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        List<StructureDTO> tableData = structureService.getStructuresForDateRange(startDate, endDate, habitNames);
        return tableData;
    }
    
    @GetMapping("/habits/info/{id}")
    public String getHabitInfo(@PathVariable Integer id, Model model) {
        Habit habit = habitService.getHabitById(id);
        if (habit == null) {
            return "error-page"; // Replace with the name of your error page template
        }
        model.addAttribute("habit", habit);
        return "info"; // This will look for info.html in the templates folder
    }

    @GetMapping("/habits/rules")
    public String showRuleSetting(Model model) {
        List<Habit> allHabits = habitService.getAllHabits();
        List<Habit> activeHabits = allHabits.stream()
            .filter(Habit::getActive)
            .collect(Collectors.toList());

        List<RuleDTO> leftHabits = activeHabits.stream()
            .map(habit -> RuleDTO.builder()
            .id(habit.getId())
            .name(habit.getName())
            .frequency(habit.getFrequency())
            .streak(habit.getStreak())
            .build())
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .collect(Collectors.toList());

        model.addAttribute("leftHabits", leftHabits);
        model.addAttribute("rightHabits", leftHabits);
        return "rule-setting";
    }
    @PostMapping("/habits/streaks")
    @ResponseBody
    public List< Pair< Integer, Integer > > getStreaks(@RequestBody List< Integer > habitIds) {
        // Assuming habitIds is a list of habit IDs for which you want to get streaks
        List< Pair< Integer, Integer > > streaks = habitService.getStreaks(habitIds);
        return streaks;
    }

    @GetMapping("/habits/inactive")
    @ResponseBody
    public List<HabitDTO> getInactiveHabits() {
        List<HabitDTO> inactiveHabits = habitService.getAllInactiveHabitsAsDTOs();
        return inactiveHabits;
    }
    

    
}
