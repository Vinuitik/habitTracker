package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** ANY /relay/api/devices/{id}/v1/** → "req" frame down the device's uplink → its "res" frame back. */
@RestController
public class RelayProxyController {

    private final DeviceService devices;
    private final UplinkRegistry uplinks;
    private final long timeoutMs;

    public RelayProxyController(DeviceService devices, UplinkRegistry uplinks,
                                @Value("${relay.proxy.timeout-ms:30000}") long timeoutMs) {
        this.devices = devices;
        this.uplinks = uplinks;
        this.timeoutMs = timeoutMs;
    }

    @RequestMapping("/relay/api/devices/{id}/v1/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String id, HttpServletRequest req)
            throws IOException, InterruptedException {
        if (devices.owned(SecurityUtils.getCurrentUserId(), id).isEmpty()) return error(404, "not found");

        byte[] in = req.getInputStream().readNBytes(UplinkRegistry.MAX_BODY_BYTES + 1);
        if (in.length > UplinkRegistry.MAX_BODY_BYTES) return error(413, "request too large");

        String uri = req.getRequestURI().substring(req.getContextPath().length());
        String path = uri.substring(uri.indexOf("/v1", "/relay/api/devices/".length()));
        if (req.getQueryString() != null) path += "?" + req.getQueryString();

        Optional<UplinkRegistry.Request> sent = uplinks.request(id, req.getMethod(), path, new String(in, UTF_8));
        if (sent.isEmpty()) return error(503, "device offline");
        UplinkRegistry.Response r;
        try {
            r = sent.get().future().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            uplinks.cancel(sent.get().id());
            return error(504, "device timed out");
        } catch (ExecutionException e) {
            return error(503, "device offline");
        }

        ResponseEntity.BodyBuilder out = ResponseEntity.status(r.status());
        if (!r.contentType().isBlank()) out.header(HttpHeaders.CONTENT_TYPE, r.contentType());
        return out.body(r.body().getBytes(UTF_8));
    }

    private static ResponseEntity<byte[]> error(int status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"" + message + "\"}").getBytes(UTF_8));
    }
}
