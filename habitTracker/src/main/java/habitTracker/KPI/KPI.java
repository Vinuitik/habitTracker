package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

@Document(collection = "kpis")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPI {
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String name; // unique name used as key
    
    private String description;
    
    private Boolean higherIsBetter; // true if higher values are better, false if lower is better
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    private Boolean active;
}
