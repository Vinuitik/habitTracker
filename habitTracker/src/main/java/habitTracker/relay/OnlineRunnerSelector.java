package habitTracker.relay;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks which of a user's paired runners a server-initiated job (e.g. KPI capability generation) goes to:
 * only devices with a live uplink; prefer the one most recently used for a job (in-memory, lost on restart),
 * then the most recent lastSeenAt.
 */
@Component
public class OnlineRunnerSelector {

    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final Map<String, Instant> lastJobAt = new ConcurrentHashMap<>();

    public OnlineRunnerSelector(DeviceService devices, UplinkRegistry uplinks) {
        this.devices = devices;
        this.uplinks = uplinks;
    }

    /** deviceId of an online runner owned by userId, or empty. */
    public Optional<String> pick(String userId) {
        return devices.list(userId).stream()
                .filter(d -> uplinks.isOnline(d.getId()))
                .max(Comparator.comparing((Device d) -> lastJobAt.getOrDefault(d.getId(), Instant.EPOCH))
                        .thenComparing(d -> d.getLastSeenAt() == null ? Instant.EPOCH : d.getLastSeenAt()))
                .map(Device::getId);
    }

    public void markUsed(String deviceId) {
        lastJobAt.put(deviceId, Instant.now());
    }
}
