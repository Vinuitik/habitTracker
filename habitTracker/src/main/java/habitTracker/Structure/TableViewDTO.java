package habitTracker.Structure;

import lombok.Data;
import java.time.LocalDate;
import java.util.Map;

@Data
public class TableViewDTO {
    private LocalDate date;
    private Map<String, Boolean> habitStatuses;
}