package updater.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document(collection = "habit_structures")
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public class HabitStructure {
    @Id
    private String id;
    private Integer habitId;
    private LocalDate structureDate;
    private Boolean completed;
}