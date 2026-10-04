package habitTracker.Project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

// A user's project, linked 1:1 to a Trello board (trelloBoardId). Keys live in the MCP container, not here.
@Document(collection = "projects")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    @Id
    private String id;
    private String name;
    private String description;
    @Indexed
    private String userId;
    private String trelloBoardId;
    private LocalDateTime createdAt;
}
