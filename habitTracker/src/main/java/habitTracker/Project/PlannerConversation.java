package habitTracker.Project;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// One planner chat for a project: user ⇄ Claude Code on the user's runner. CLARIFY jobs ask questions,
// a DRAFT job (after /lock) proposes lists/cards/schedule; /apply is the only Trello write.
@Document(collection = "planner_conversations")
@Data
@NoArgsConstructor
public class PlannerConversation {

    public enum Phase { CLARIFY, DRAFT }

    @Id
    private String id;
    private String projectId;
    private String userId;
    private Phase phase = Phase.CLARIFY;
    private List<Message> messages = new ArrayList<>();
    // Open questions from the latest CLARIFY result.
    private List<String> questions = new ArrayList<>();
    private Draft draft;
    // The only job that can be polled; older jobs are orphaned on the runner.
    private String currentJobId;
    // Last job whose outcome (result or failure) has been stored; == currentJobId → not running.
    private String resultJobId;
    // Failure of resultJobId (runner error or unusable result), else null.
    private String error;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant appliedAt;
    private String appliedDeadline;
    private String appliedPace;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;   // user | ai
        private String text;
        // Questions the AI asked with this message (kept for the prompt history, not returned as a message field).
        private List<String> questions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Draft {
        private List<DraftList> lists = new ArrayList<>();
        private List<ScheduleItem> schedule = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftList {
        private String name;
        private List<DraftCard> cards = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DraftCard {
        private String title;
        private String desc;
        private Integer importance;   // 3 = Must, 2 = Should, 1 = Could
        private String due;           // YYYY-MM-DD, optional
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleItem {
        private String date;          // YYYY-MM-DD
        private String title;         // card title it schedules
        private Integer importance;
    }
}
