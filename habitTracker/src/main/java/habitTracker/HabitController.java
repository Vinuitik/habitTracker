package habitTracker;


import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
        Structure structure = structureService.getLatestStructure();
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
    
}
