package habitTracker.Project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Read-through mirror of a project's Trello board (one doc per project). The "_meta" list and its
// cards are never stored. id/userId/lastActionId are internal (not serialized to the client).
@Document(collection = "trello_board_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardSnapshot {
    @Id
    @JsonIgnore
    private String id;
    @Indexed(unique = true)
    private String projectId;
    @JsonIgnore
    private String userId;
    private String boardId;
    private String name;
    private String url;
    private Instant syncedAt;
    // Reserved for action-based incremental sync (unused; sync compares dateLastActivity instead).
    @JsonIgnore
    private String lastActionId;
    // Trello board.dateLastActivity at last full fetch; the cheap change probe compares against it.
    @JsonIgnore
    private String dateLastActivity;
    // Trello answered 404 for the board (deleted/closed/access lost). Project is kept; cleared on next successful probe.
    private boolean boardMissing;
    @Builder.Default
    private List<ListView> lists = new ArrayList<>();
    @Builder.Default
    private List<CardView> cards = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListView {
        private String id;
        private String name;
        private Double pos;
        private boolean closed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LabelView {
        private String id;
        private String name;
        private String color;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BadgesView {
        private Integer checkItems;
        private Integer checkItemsChecked;
        private Boolean description;
        private Integer comments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardView {
        private String id;
        private String idList;
        private String name;
        private String desc;
        private Double pos;
        private String due;
        private Boolean dueComplete;
        private boolean closed;
        private List<LabelView> labels = new ArrayList<>();
        private BadgesView badges;
        private String url;
        // Computed on sync: label "done" or list Done/Completed.
        private boolean done;
    }
}
