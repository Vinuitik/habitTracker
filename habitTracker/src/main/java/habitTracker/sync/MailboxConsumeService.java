package habitTracker.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.KPI.KPIService;
import habitTracker.Structure.StructureService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Drains each connected user's own Drive "_mailbox_requests" folder and replays whatever
// intents landed there while the server was unreachable. Delete-after-success is the
// correctness hinge: a mailbox file is removed only once every request inside it committed —
// anything left over is retried on the next pass (requests are idempotent, so a retry is safe).
@Service
@RequiredArgsConstructor
public class MailboxConsumeService {

    private final UserSyncSettingsRepository syncSettingsRepository;
    private final ConsumedSyncRequestRepository consumedRepository;
    private final DriveService driveService;
    private final VaultEncryptionService encryptionService;
    private final StructureService structureService;
    private final KPIService kpiService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consumeOnStartup() {
        consumeAll();
    }

    // Matches this codebase's existing convention (habitTracker.updater.UpdateScheduler) of a
    // hardcoded cron literal rather than an env-configurable schedule.
    @Scheduled(cron = "0 */15 * * * ?")
    public void consumeScheduled() {
        consumeAll();
    }

    public void consumeAll() {
        for (UserSyncSettings settings : syncSettingsRepository.findByDriveRefreshTokenNotNull()) {
            try {
                consumeForUser(settings);
            } catch (Exception e) {
                System.err.println("[MailboxConsume] failed for user " + settings.getUserId() + ": " + e.getMessage());
            }
        }
    }

    private void consumeForUser(UserSyncSettings settings) {
        String accessToken = driveService.getAccessToken(settings.getDriveRefreshToken());
        List<DriveService.DriveFile> files = driveService.listFiles(accessToken, settings.getMailboxFolderId());

        for (DriveService.DriveFile file : files) {
            try {
                byte[] wire = driveService.downloadFile(accessToken, file.id());
                byte[] plain = encryptionService.decrypt(settings.getEncryptionKey(), wire);
                MailboxBatch batch = objectMapper.readValue(plain, MailboxBatch.class);

                boolean allCommitted = true;
                for (SyncRequest req : batch.requests()) {
                    if (consumedRepository.existsById(req.requestId())) {
                        continue; // already applied on a prior pass — pure no-op
                    }
                    if (applyRequest(settings.getUserId(), req)) {
                        consumedRepository.save(ConsumedSyncRequest.builder()
                                .requestId(req.requestId())
                                .userId(settings.getUserId())
                                .consumedAt(System.currentTimeMillis())
                                .build());
                    } else {
                        allCommitted = false;
                    }
                }

                if (allCommitted) {
                    driveService.deleteFile(accessToken, file.id());
                }
            } catch (Exception e) {
                // Leave this file for the next pass — never delete on a failed/undecryptable batch.
                System.err.println("[MailboxConsume] file " + file.name() + " failed: " + e.getMessage());
            }
        }
    }

    private boolean applyRequest(String userId, SyncRequest req) {
        try {
            switch (req.kind()) {
                case "habit-complete" -> {
                    HabitCompletePayload p = objectMapper.convertValue(req.payload(), HabitCompletePayload.class);
                    structureService.updateHabitCompletionForUser(userId, p.habitId(), p.completed(), p.date());
                }
                case "kpi-value" -> {
                    KpiValuePayload p = objectMapper.convertValue(req.payload(), KpiValuePayload.class);
                    kpiService.addKPIDataForUser(userId, p.kpiName(), p.date(), p.value());
                }
                default -> System.err.println("[MailboxConsume] unknown request kind: " + req.kind());
            }
            return true;
        } catch (Exception e) {
            System.err.println("[MailboxConsume] request " + req.requestId() + " (" + req.kind() + ") failed: " + e.getMessage());
            return false;
        }
    }

    public record MailboxBatch(String deviceId, List<SyncRequest> requests) {}
    public record SyncRequest(String requestId, String kind, long ts, Map<String, Object> payload) {}
    public record HabitCompletePayload(Integer habitId, Boolean completed, LocalDate date) {}
    public record KpiValuePayload(String kpiName, LocalDate date, Double value) {}
}
