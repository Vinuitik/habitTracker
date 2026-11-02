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
    // true = board adopted from the user's Trello; deleting the project only unlinks it.
    private boolean imported;
    // Relay runner link (all null = not linked): the user's device, the runner's registered-folder id, its display name.
    private String runnerDeviceId;
    private String runnerProjectId;
    private String folderName;
    // Last map job started for this project; polls for any other job id are 404.
    private String mapJobId;
}
