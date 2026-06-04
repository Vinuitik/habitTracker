package habitTracker;

import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import habitTracker.Habit.Habit;
import habitTracker.Habit.HabitDTO;
import habitTracker.Habit.HabitService;
import habitTracker.Rules.RuleDTO;
import habitTracker.Structure.StructureDTO;
import habitTracker.Structure.StructureService;
import habitTracker.util.Pair;

@RestController
@RequiredArgsConstructor
public class HabitReadController {

    private final HabitService habitService;
    private final StructureService structureService;

    @GetMapping("/api/today")
    public Map<String, Object> getToday() {
        StructureDTO structure = structureService.getTodayStructure();
        List<Map<String, Object>> habits = new ArrayList<>();
        if (structure.getHabits() != null) {
            structure.getHabits().forEach((pair, completed) -> {
                Map<String, Object> h = new HashMap<>();
                h.put("id", pair.getValue());
                h.put("name", pair.getKey());
                h.put("completed", completed);
                Habit detail = structure.getHabitDetails() != null
                        ? structure.getHabitDetails().get(pair) : null;
                h.put("defaultMade", detail != null && Boolean.TRUE.equals(detail.getDefaultMade()));
                habits.add(h);
            });
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("date", structure.getDate() != null ? structure.getDate().toString() : LocalDate.now().toString());
        resp.put("habits", habits);
        return resp;
    }

    @GetMapping("/api/habits")
    public List<HabitDTO> listHabits() {
        List<HabitDTO> habits = habitService.getAllActiveHabitsAsDTOs();
        habits.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return habits;
    }

    @GetMapping("/api/habits/table")
    public Map<String, Object> getHabitTable(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(7);
        if (endDate == null) endDate = LocalDate.now();

        List<Pair<String, Integer>> habitNames = habitService.getAllUniqueHabitNamesIds();
        habitNames.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        List<StructureDTO> tableData = structureService.getStructuresForDateRange(startDate, endDate, habitNames);

        Map<String, Object> resp = new HashMap<>();
        resp.put("startDate", startDate.toString());
        resp.put("endDate", endDate.toString());
        resp.put("habitNames", habitNames);
        resp.put("tableData", tableData);
        return resp;
    }

    // kept for backward-compat with existing table JS
    @GetMapping(value = "/habits/tableAsync", produces = "application/json")
    public List<StructureDTO> getHabitTableData(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(7);
        if (endDate == null) endDate = LocalDate.now();
        List<Pair<String, Integer>> habitNames = habitService.getAllUniqueHabitNamesIds();
        habitNames.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        return structureService.getStructuresForDateRange(startDate, endDate, habitNames);
    }

    @GetMapping("/api/habits/rules")
    public List<RuleDTO> getRulesHabits() {
        List<Habit> allHabits = habitService.getAllHabits();
        return allHabits.stream()
                .filter(Habit::getActive)
                .map(h -> RuleDTO.builder()
                        .id(h.getId())
                        .name(h.getName())
                        .frequency(h.getFrequency())
                        .streak(h.getStreak())
                        .build())
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .collect(Collectors.toList());
    }

    @GetMapping("/api/habits/{id}")
    public Habit getHabit(@PathVariable Integer id) {
        return habitService.getHabitById(id);
    }

    @GetMapping("/api/habits/inactive")
    public List<HabitDTO> getInactiveHabits() {
        return habitService.getAllInactiveHabitsAsDTOs();
    }

    // kept for backward-compat with existing today-page JS
    @GetMapping("/habits/inactive")
    public List<HabitDTO> getInactiveHabitsLegacy() {
        return habitService.getAllInactiveHabitsAsDTOs();
    }

    @PostMapping("/habits/streaks")
    public List<Pair<Integer, Integer>> getStreaks(@RequestBody List<Integer> habitIds) {
        return habitService.getStreaks(habitIds);
    }
}
