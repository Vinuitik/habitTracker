package habitTracker.Project;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

// List-row view: Project fields (names unchanged) + snapshot-derived counts (null if never synced).
@Data
public class ProjectView {
    private String id;
    private String name;
    private String description;
    private String userId;
    private String trelloBoardId;
    private LocalDateTime createdAt;
    private boolean imported;
    private Integer cardCount;
    private Integer doneCount;
    private Instant syncedAt;

    static ProjectView of(Project p, BoardSnapshot s) {
        ProjectView v = new ProjectView();
        v.id = p.getId();
        v.name = p.getName();
        v.description = p.getDescription();
        v.userId = p.getUserId();
        v.trelloBoardId = p.getTrelloBoardId();
        v.createdAt = p.getCreatedAt();
        v.imported = p.isImported();
        if (s != null) {
            var open = s.getCards().stream().filter(c -> !c.isClosed()).toList();
            v.cardCount = open.size();
            v.doneCount = (int) open.stream().filter(BoardSnapshot.CardView::isDone).count();
            v.syncedAt = s.getSyncedAt();
        }
        return v;
    }
}
