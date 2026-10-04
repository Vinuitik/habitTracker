package habitTracker.Project;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Single-use delete confirmation tokens; same in-memory shape as sync.PairingCodeService.
// Lost on restart (user just requests again). Single-node only.
@Service
public class ProjectDeleteTokenService {

    private static final long DEFAULT_TTL_MS = 60_000;

    private final long ttlMs;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ProjectDeleteTokenService() {
        this(DEFAULT_TTL_MS);
    }

    // Package-private: tests use a short TTL.
    ProjectDeleteTokenService(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public String issue(String projectId, String userId) {
        String token = UUID.randomUUID().toString();
        pending.put(token, new Pending(projectId, userId, System.currentTimeMillis() + ttlMs));
        return token;
    }

    /** Consumes the token (atomic remove): true only if it exists, is unexpired and matches project+user. */
    public boolean redeem(String token, String projectId, String userId) {
        if (token == null) return false;
        Pending p = pending.remove(token);
        return p != null
                && p.expiryMs() >= System.currentTimeMillis()
                && p.projectId().equals(projectId)
                && p.userId().equals(userId);
    }

    private record Pending(String projectId, String userId, long expiryMs) {}
}
