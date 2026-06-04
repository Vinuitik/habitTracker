package habitTracker.KPI;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/kpis")
public class KPIController {

    private final KPIService kpiService;
    private final HabitService habitService;

    @GetMapping
    public List<KPIDTO> listKPIs() {
        return kpiService.getAllActiveKPIs();
    }

    @GetMapping("/available-habits")
    public List<Habit> availableHabits() {
        return habitService.getAllHabits().stream()
                .filter(Habit::getActive)
                .collect(Collectors.toList());
    }

    @GetMapping("/dashboard")
    public List<KPIDTO> dashboardKPIs() {
        return kpiService.getAllActiveKPIs();
    }

    @PostMapping("/create")
    public ResponseEntity<?> createKPI(@RequestBody Map<String, Object> body) {
        try {
            String name = ((String) body.get("name")).trim();
            String description = body.get("description") != null ? ((String) body.get("description")).trim() : "";
            Boolean higherIsBetter = (Boolean) body.get("higherIsBetter");
            @SuppressWarnings("unchecked")
            List<Integer> habitIds = body.get("habitIds") != null
                    ? ((List<?>) body.get("habitIds")).stream().map(v -> ((Number) v).intValue()).collect(Collectors.toList())
                    : null;
            kpiService.createKPI(name, description, higherIsBetter, habitIds);
            return ResponseEntity.ok(Map.of("message", "KPI created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{name}/data")
    public List<KPIDataDTO> getKPIData(@PathVariable String name,
                                       @RequestParam(defaultValue = "weekly") String period) {
        return switch (period.toLowerCase()) {
            case "monthly" -> kpiService.getMonthlyKPIData(name);
            default -> kpiService.getWeeklyKPIData(name);
        };
    }

    @PostMapping("/{name}/data")
    public ResponseEntity<String> addKPIData(@PathVariable String name,
                                             @RequestParam LocalDate date,
                                             @RequestParam Double value) {
        try {
            kpiService.addKPIData(name, date, value);
            return ResponseEntity.ok("Data added successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<String> deleteKPI(@PathVariable String name) {
        try {
            kpiService.deleteKPI(name);
            return ResponseEntity.ok("KPI deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/habits/{habitId}")
    public List<KPIDTO> getKPIsByHabit(@PathVariable Integer habitId) {
        return kpiService.getKPIsByHabitId(habitId);
    }
}
