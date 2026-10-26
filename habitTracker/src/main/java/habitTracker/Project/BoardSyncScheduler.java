package habitTracker.Project;

import habitTracker.integrations.trello.TrelloAuthException;
import habitTracker.integrations.trello.TrelloCredentialRepository;
import habitTracker.integrations.trello.TrelloGatewayException;
import habitTracker.integrations.trello.TrelloNotConnectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Background mirror refresh: every 15 min probes each snapshotted board's dateLastActivity and refetches
// only changed ones, so edits made directly in Trello reach the app. Sequential, per-board and per-user
// failure isolation (same shape as sync.MailboxConsumeService). Single-node, in-process.
@Service
@RequiredArgsConstructor
public class BoardSyncScheduler {

    private final BoardSnapshotRepository snapshotRepository;
    private final ProjectRepository projectRepository;
    private final TrelloCredentialRepository credentialRepository;
    private final BoardSnapshotService snapshots;

    // Pause between Trello calls: keeps well under ~100 req/10s per token. Tests set 0.
    long pauseMs = 200;

    // Hardcoded cron literal, same convention as MailboxConsumeService / UpdateScheduler.
    @Scheduled(cron = "0 */15 * * * ?")
    public void syncScheduled() {
        syncAll();
    }

    public void syncAll() {
        Map<String, List<BoardSnapshot>> byUser = new LinkedHashMap<>();
        for (BoardSnapshot s : snapshotRepository.findAll()) {
            if (s.getUserId() != null) byUser.computeIfAbsent(s.getUserId(), k -> new java.util.ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<BoardSnapshot>> e : byUser.entrySet()) {
            try {
                if (!syncUser(e.getKey(), e.getValue())) return; // rate limited: stop the whole run
            } catch (Exception ex) {
                System.err.println("[BoardSync] failed for user " + e.getKey() + ": " + ex.getMessage());
            }
        }
    }

    /** @return false when Trello rate-limited (429) and the run must stop. */
    private boolean syncUser(String userId, List<BoardSnapshot> list) {
        if (credentialRepository.findByUserId(userId).isEmpty()) return true;
        for (BoardSnapshot s : list) {
            try {
                Project p = projectRepository.findById(s.getProjectId()).orElse(null);
                if (p == null) continue;
                snapshots.syncIfChanged(p, s);
            } catch (TrelloAuthException | TrelloNotConnectedException ex) {
                System.err.println("[BoardSync] Trello token invalid/missing for user " + userId + ", skipping their boards");
                return true;
            } catch (TrelloGatewayException ex) {
                if (ex.getStatus() == 429) {
                    System.err.println("[BoardSync] Trello rate limited, stopping run");
                    return false;
                }
                System.err.println("[BoardSync] board " + s.getBoardId() + " failed: " + ex.getMessage());
            } catch (Exception ex) {
                System.err.println("[BoardSync] board " + s.getBoardId() + " failed: " + ex.getMessage());
            } finally {
                pause();
            }
        }
        return true;
    }

    private void pause() {
        if (pauseMs <= 0) return;
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
