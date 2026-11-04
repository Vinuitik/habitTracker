package habitTracker.relay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Server-initiated request/response to a user's runner over its uplink ("req" frame → "res" frame).
 * Shared by RelayProxyController (browser → runner) and hub features (RunnerJobService).
 * Blocks the calling thread up to relay.proxy.timeout-ms.
 */
@Component
public class RunnerClient {

    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final long timeoutMs;

    public RunnerClient(DeviceService devices, UplinkRegistry uplinks,
                        @Value("${relay.proxy.timeout-ms:30000}") long timeoutMs) {
        this.devices = devices;
        this.uplinks = uplinks;
        this.timeoutMs = timeoutMs;
    }

    /** Ownership-checked call: deviceId must belong to userId, else DEVICE_NOT_FOUND. Any status is returned. */
    public UplinkRegistry.Response call(String userId, String deviceId, String method, String path, String body) {
        return call(userId, deviceId, method, path, body, timeoutMs);
    }

    /** Same, with its own wait (e.g. a sandboxed collector run that may take up to a minute on the runner). */
    public UplinkRegistry.Response call(String userId, String deviceId, String method, String path, String body,
                                        long waitMs) {
        if (devices.owned(userId, deviceId).isEmpty()) {
            throw new RunnerUnavailableException(RunnerUnavailableException.Reason.DEVICE_NOT_FOUND, "device not found");
        }
        return exchange(deviceId, method, path, body, waitMs);
    }

    /** No ownership check: callers must have done it. Throws OFFLINE / TIMEOUT. */
    UplinkRegistry.Response exchange(String deviceId, String method, String path, String body) {
        return exchange(deviceId, method, path, body, timeoutMs);
    }

    private UplinkRegistry.Response exchange(String deviceId, String method, String path, String body, long timeoutMs) {
        Optional<UplinkRegistry.Request> sent = uplinks.request(deviceId, method, path, body == null ? "" : body);
        if (sent.isEmpty()) throw offline();
        try {
            return sent.get().future().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            uplinks.cancel(sent.get().id());
            throw new RunnerUnavailableException(RunnerUnavailableException.Reason.TIMEOUT, "device timed out");
        } catch (ExecutionException e) {
            throw offline();
        } catch (InterruptedException e) {
            uplinks.cancel(sent.get().id());
            Thread.currentThread().interrupt();
            throw offline();
        }
    }

    private static RunnerUnavailableException offline() {
        return new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "device offline");
    }
}
