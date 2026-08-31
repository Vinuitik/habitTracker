package habitTracker.KPI;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

// Per-user Trello API credential, deliberately NOT reusing the app's own session/JWT auth
// (habitTracker.auth) — this is a third-party API key+token pair the user pastes in from
// their own Trello account, unrelated to how they're authenticated into HabitTracker itself.
// One Trello connection per user (unique index on userId): board/list id, by contrast, live on
// each KPI's own proxyConfig (see KPI.proxyConfig javadoc) since a user can point different KPIs
// at different Trello lists while sharing the same underlying API key+token.
@Document(collection = "trello_credentials")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TrelloCredential {
    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    // Trello "application key" — see https://trello.com/power-ups/admin, obtained per-user here
    // since this app has no registered Trello Power-Up of its own to hold a single shared key.
    private String apiKey;

    // Trello API token authorizing this app's apiKey to read the user's boards/lists.
    private String token;

    private LocalDateTime updatedAt;
}
