package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

@Document(collection = "kpis")
@CompoundIndex(def = "{'name': 1, 'userId': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class KPI {
    @Id
    private String id;
    
    private String name; // unique per user (compound index with userId)
    
    private String description;
    
    private Boolean higherIsBetter; // true if higher values are better, false if lower is better
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    private Boolean active;

    @Indexed
    private String userId;
}
