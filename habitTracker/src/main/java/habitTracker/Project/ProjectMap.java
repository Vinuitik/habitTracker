package habitTracker.Project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Repo map of a runner-linked project, built by Claude Code on the user's runner (one doc per project).
@Document(collection = "project_maps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMap {
    @Id
    private String id;
    private String projectId;
    private String userId;
    // Runner job that produced this map; a repeated poll of the same done job doesn't rewrite it.
    private String jobId;
    private String commitSha;
    private Instant builtAt;
    // Markdown: subsystem index + cross-subsystem links.
    private String index;
    @Builder.Default
    private List<Subsystem> subsystems = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subsystem {
        private String name;
        private List<String> paths = new ArrayList<>();
        // Markdown, FLOWS style (Files: line, arrow chains, Change Index).
        private String notes;
    }
}
