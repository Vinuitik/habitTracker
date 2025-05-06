package updater.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document(collection = "last_run_date")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LastRunDate {
    @Id
    private LocalDate date; // Use date as the unique identifier
}