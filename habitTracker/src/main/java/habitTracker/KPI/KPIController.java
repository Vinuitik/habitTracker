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
@RequestMapping("/api/kpis")
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
            Boolean autoFillEnabled = (Boolean) body.get("autoFillEnabled");
            Double defaultValue = body.get("defaultValue") != null ? ((Number) body.get("defaultValue")).doubleValue() : null;
            kpiService.createKPI(name, description, higherIsBetter, habitIds, autoFillEnabled, defaultValue);
            return ResponseEntity.ok(Map.of("message", "KPI created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{name}/default-fill")
    public ResponseEntity<?> updateDefaultFill(@PathVariable String name, @RequestBody Map<String, Object> body) {
        try {
            Boolean autoFillEnabled = (Boolean) body.get("autoFillEnabled");
            Double defaultValue = body.get("defaultValue") != null ? ((Number) body.get("defaultValue")).doubleValue() : null;
            KPIDTO updated = kpiService.updateDefaultFillSettings(name, autoFillEnabled, defaultValue);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{name}/data")
    public ResponseEntity<?> getKPIData(@PathVariable String name,
                                        @RequestParam(defaultValue = "weekly") String period,
                                        @RequestParam(required = false) LocalDate startDate,
                                        @RequestParam(required = false) LocalDate endDate) {
        List<KPIDataDTO> result = switch (period.toLowerCase()) {
            case "monthly"  -> kpiService.getMonthlyKPIData(name);
            case "alltime"  -> kpiService.getAllTimeKPIData(name);
            case "custom"   -> {
                if (startDate == null || endDate == null) {
                    yield List.of();
                }
                yield kpiService.getKPIDataForDateRange(name, startDate, endDate);
            }
            default         -> kpiService.getWeeklyKPIData(name);
        };
        return ResponseEntity.ok(result);
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
