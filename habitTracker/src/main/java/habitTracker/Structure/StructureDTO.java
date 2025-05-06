package habitTracker.Structure;

import java.time.LocalDate;
import java.util.Map;

import habitTracker.util.Pair;
import lombok.Data;

@Data
public class StructureDTO {

    public StructureDTO(LocalDate date) {
        this.date = date;
    }

    public StructureDTO() {
    }

    private LocalDate date;

    private Map<Pair<String, Integer>, Boolean> habits; // Use custom Pair
}
