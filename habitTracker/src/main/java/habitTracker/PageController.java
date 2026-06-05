package habitTracker;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {

    @GetMapping("/landing")
    public String landing() { return "forward:/landing.html"; }

    @GetMapping({"/", "/habit"})
    public String today() { return "forward:/index.html"; }

    @GetMapping("/habits/list")
    public String habitsList() { return "forward:/habits-list.html"; }

    @GetMapping("/habits/table")
    public String habitsTable() { return "forward:/habit-table.html"; }

    @GetMapping("/habits/rules")
    public String rulesPage() { return "forward:/rule-setting.html"; }

    @GetMapping("/habits/add")
    public String habitAdd() { return "forward:/habit-add.html"; }

    @GetMapping("/habits/edit/{id}")
    public String habitEdit(@PathVariable String id) { return "forward:/habit-edit.html"; }

    @GetMapping("/habits/info/{id}")
    public String habitInfo(@PathVariable String id) { return "forward:/habit-info.html"; }

    @GetMapping("/kpis")
    public String kpiList() { return "forward:/kpi-list.html"; }

    @GetMapping("/kpis/create")
    public String kpiCreate() { return "forward:/kpi-create.html"; }

    @GetMapping("/kpis/dashboard")
    public String kpiDashboard() { return "forward:/kpi-dashboard.html"; }
}
