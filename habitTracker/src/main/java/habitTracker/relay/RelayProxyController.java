package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** ANY /relay/api/devices/{id}/v1/** → RunnerClient.exchange() ("req" frame down the uplink) → its "res" frame back. */
@RestController
public class RelayProxyController {

    private final DeviceService devices;
    private final RunnerClient runners;

    public RelayProxyController(DeviceService devices, RunnerClient runners) {
        this.devices = devices;
        this.runners = runners;
    }

    @RequestMapping("/relay/api/devices/{id}/v1/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String id, HttpServletRequest req) throws IOException {
        if (devices.owned(SecurityUtils.getCurrentUserId(), id).isEmpty()) return error(404, "not found");

        byte[] in = req.getInputStream().readNBytes(UplinkRegistry.MAX_BODY_BYTES + 1);
        if (in.length > UplinkRegistry.MAX_BODY_BYTES) return error(413, "request too large");

        String uri = req.getRequestURI().substring(req.getContextPath().length());
        String path = uri.substring(uri.indexOf("/v1", "/relay/api/devices/".length()));
        if (req.getQueryString() != null) path += "?" + req.getQueryString();

        UplinkRegistry.Response r;
        try {
            r = runners.exchange(id, req.getMethod(), path, new String(in, UTF_8));
        } catch (RunnerUnavailableException e) {
            return error(e.getReason() == RunnerUnavailableException.Reason.TIMEOUT ? 504 : 503, e.getMessage());
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
