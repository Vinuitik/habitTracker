package habitTracker.KPI;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@RequestMapping("/kpis")
public class KPIController {
    
    private final KPIService kpiService;
    private final HabitService habitService;
    
    @GetMapping
    public String showKPIList(Model model) {
        List<KPIDTO> kpis = kpiService.getAllActiveKPIs();
        model.addAttribute("kpis", kpis);
        return "kpi-list";
    }
    
    @GetMapping("/create")
    public String showCreateKPIForm(Model model) {
        List<Habit> activeHabits = habitService.getAllHabits().stream()
                .filter(Habit::getActive)
                .collect(Collectors.toList());
        model.addAttribute("availableHabits", activeHabits);
        return "kpi-create";
    }
    
    @PostMapping("/create")
    public String createKPI(@RequestParam String name,
                           @RequestParam String description,
                           @RequestParam Boolean higherIsBetter,
                           @RequestParam(required = false) List<Integer> habitIds,
                           RedirectAttributes redirectAttributes) {
        try {
            kpiService.createKPI(name.trim(), description.trim(), higherIsBetter, habitIds);
            redirectAttributes.addFlashAttribute("successMessage", "KPI '" + name + "' created successfully!");
            return "redirect:/kpis";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/kpis/create";
        }
    }
    
    @GetMapping("/dashboard")
    public String showKPIDashboard(Model model) {
        List<KPIDTO> kpis = kpiService.getAllActiveKPIs();
        model.addAttribute("kpis", kpis);
        return "kpi-dashboard";
    }
    
    @GetMapping("/{name}/data")
    @ResponseBody
    public List<KPIDataDTO> getKPIData(@PathVariable String name,
                                      @RequestParam(defaultValue = "weekly") String period) {
        switch (period.toLowerCase()) {
            case "weekly":
                return kpiService.getWeeklyKPIData(name);
            case "monthly":
                return kpiService.getMonthlyKPIData(name);
            default:
                return kpiService.getWeeklyKPIData(name);
        }
    }
    
    @PostMapping("/{name}/data")
    @ResponseBody
    public String addKPIData(@PathVariable String name,
                           @RequestParam LocalDate date,
                           @RequestParam Double value) {
        try {
            kpiService.addKPIData(name, date, value);
            return "Data added successfully";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    @DeleteMapping("/{name}")
    @ResponseBody
    public String deleteKPI(@PathVariable String name) {
        try {
            kpiService.deleteKPI(name);
            return "KPI deleted successfully";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    @GetMapping("/habits/{habitId}")
    @ResponseBody
    public List<KPIDTO> getKPIsByHabit(@PathVariable Integer habitId) {
        return kpiService.getKPIsByHabitId(habitId);
    }
}
