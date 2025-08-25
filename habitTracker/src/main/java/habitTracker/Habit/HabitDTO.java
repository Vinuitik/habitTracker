package habitTracker.Habit;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class HabitDTO {
    private String id; // Use String for compatibility with the frontend
    private String name;
    private Integer frequency;
    private LocalDate startDate;
    private LocalDate curDate;
    private LocalDate endDate;
    private Boolean defaultMade;

    // Static method to convert Habit to HabitDTO
    public static HabitDTO fromHabit(Habit habit) {
        return new HabitDTO(
            habit.getId().toString(), // Convert Integer to String
            habit.getName(),
            habit.getFrequency(),
            habit.getStartDate(),
            habit.getCurDate(),
            habit.getEndDate(),
            habit.getDefaultMade()
        );
    }

    // Static method to convert HabitDTO to Habit
    public static Habit toHabit(HabitDTO habitDTO) {
        return Habit.builder()
            .id(Integer.valueOf(habitDTO.getId())) // Convert String to Integer
            .name(habitDTO.getName())
            .frequency(habitDTO.getFrequency())
            .startDate(habitDTO.getStartDate())
            .curDate(habitDTO.getCurDate())
            .endDate(habitDTO.getEndDate())
            .defaultMade(habitDTO.getDefaultMade())
            .build();
    }
}
