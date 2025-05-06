package habitTracker.Structure;

import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;

@Document(collection = "habit_structures")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HabitStructure {
    @Id
    private String id = new ObjectId().toString();
    
    @Indexed
    private Integer habitId;
    
    @Indexed
    private LocalDate structureDate;
    
    private Boolean completed;
}