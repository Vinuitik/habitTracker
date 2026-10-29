package habitTracker.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * In-memory deviceId → live uplink socket, plus the requests waiting for a "res" frame.
 * Single JVM only: a restart drops every uplink (runners reconnect) and every pending request.
 */
@Component
public class UplinkRegistry {

    private static final Logger log = LoggerFactory.getLogger(UplinkRegistry.class);

    /** Max body each way (HUB.md). */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    /** Max WS text frame: a 4 MiB body grows when JSON-escaped inside the frame, so leave headroom. */
    static final int MAX_FRAME_BYTES = 2 * MAX_BODY_BYTES + 64 * 1024;
    static final long PING_MS = 30_000;
    /** No pong / frame for this long → treat the socket as half-open and close it. */
    static final long DEAD_AFTER_MS = 3 * PING_MS + 5_000;
    static final CloseStatus REPLACED = new CloseStatus(4000, "replaced by a newer connection");
    static final CloseStatus REVOKED = new CloseStatus(4001, "device revoked");

    public record Response(int status, String contentType, String body) {}

    private static final class Link {
        final WebSocketSession raw;
        final WebSocketSession out;
        volatile long lastHeard = System.currentTimeMillis();

        Link(WebSocketSession raw) {
            this.raw = raw;
            // Serialises sends: proxy threads and the ping loop write concurrently.
            this.out = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 4 * MAX_FRAME_BYTES);
        }
    }

    private record Pending(WebSocketSession raw, CompletableFuture<Response> future) {}

    private final ObjectMapper json;
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public UplinkRegistry(ObjectMapper json) {
        this.json = json;
    }

    public void register(String deviceId, WebSocketSession raw) {
        Link old = links.put(deviceId, new Link(raw));
        if (old != null) close(old, REPLACED);
    }

    /** Only removes the mapping if it still points at this socket (a replacement may already be in). */
    public void unregister(String deviceId, WebSocketSession raw) {
        links.computeIfPresent(deviceId, (k, l) -> l.raw == raw ? null : l);
        pending.entrySet().removeIf(e -> {
            if (e.getValue().raw() != raw) return false;
            e.getValue().future().completeExceptionally(new IOException("uplink closed"));
            return true;
        });
    }

    public void heard(String deviceId) {
        Link l = links.get(deviceId);
        if (l != null) l.lastHeard = System.currentTimeMillis();
    }

    public boolean isOnline(String deviceId) {
        Link l = links.get(deviceId);
        return l != null && l.raw.isOpen();
    }

    public void disconnect(String deviceId) {
        Link l = links.remove(deviceId);
        if (l != null) close(l, REVOKED);
    }

    /** Sends a "req" frame; empty when the device has no open uplink. Caller must cancel() on timeout. */
    public Optional<Request> request(String deviceId, String method, String path, String body) {
        Link l = links.get(deviceId);
        if (l == null || !l.raw.isOpen()) return Optional.empty();
        String id = UUID.randomUUID().toString();
        CompletableFuture<Response> f = new CompletableFuture<>();
        pending.put(id, new Pending(l.raw, f));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "req");
        frame.put("id", id);
        frame.put("method", method);
        frame.put("path", path);
        frame.put("body", body);
        try {
            l.out.sendMessage(new TextMessage(json.writeValueAsString(frame)));
        } catch (Exception e) {
            pending.remove(id);
            log.warn("[relay] send to device {} failed: {}", deviceId, e.toString());
            return Optional.empty();
        }
        return Optional.of(new Request(id, f));
    }

    public record Request(String id, CompletableFuture<Response> future) {}

    public void cancel(String requestId) {
        pending.remove(requestId);
    }

    /** A text frame from a runner. Unknown ids (timed out, or forged) are dropped. */
    public void onFrame(String deviceId, String payload) {
        heard(deviceId);
        try {
            JsonNode n = json.readTree(payload);
            if (!"res".equals(n.path("type").asText())) return;
            Pending p = pending.remove(n.path("id").asText());
            if (p == null) return;
            String body = n.path("body").asText("");
            if (utf8Length(body) > MAX_BODY_BYTES) {
                p.future().complete(new Response(502, "application/json", "{\"error\":\"response too large\"}"));
                return;
            }
            p.future().complete(new Response(n.path("status").asInt(502), n.path("contentType").asText(""), body));
        } catch (Exception e) {
            log.warn("[relay] bad frame from device {}: {}", deviceId, e.toString());
        }
    }

    // Cloudflare's tunnel drops a WebSocket after ~100s of silence; pings keep it busy and expose dead peers.
    @Scheduled(fixedRate = PING_MS)
    void pingAll() {
        long now = System.currentTimeMillis();
        links.forEach((id, l) -> {
            if (now - l.lastHeard > DEAD_AFTER_MS) {
                log.info("[relay] device {} silent for {}ms, closing", id, now - l.lastHeard);
                links.remove(id, l);
                close(l, CloseStatus.SESSION_NOT_RELIABLE);
                return;
            }
            try {
                l.out.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[]{'r'})));
            } catch (Exception e) {
                links.remove(id, l);
                close(l, CloseStatus.SESSION_NOT_RELIABLE);
            }
        });
    }

    private static void close(Link l, CloseStatus status) {
        try {
            l.raw.close(status);
        } catch (Exception ignored) {
            // already gone
        }
    }

    static int utf8Length(String s) {
        if (s.length() > MAX_BODY_BYTES) return s.getBytes(UTF_8).length;
        if (s.length() * 3L <= MAX_BODY_BYTES) return s.length();
        return s.getBytes(UTF_8).length;
    }
}
