package habitTracker.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * /relay/uplink: runnerd dials out with "Authorization: Bearer <device token>".
 * The token is checked in beforeHandshake, so an unknown token gets a plain 401 and no upgrade.
 */
@Component
@RequiredArgsConstructor
public class UplinkHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    static final String DEVICE_ID = "relay.deviceId";

    private final DeviceService devices;
    private final UplinkRegistry registry;

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                                   Map<String, Object> attrs) {
        String auth = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7) : null;
        var device = devices.findByToken(token);
        if (device.isEmpty()) {
            res.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attrs.put(DEVICE_ID, device.get().getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        String id = deviceId(s);
        registry.register(id, s);
        devices.touch(id);
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
        registry.onFrame(deviceId(s), msg.getPayload());
    }

    @Override
    protected void handlePongMessage(WebSocketSession s, PongMessage msg) {
        String id = deviceId(s);
        registry.heard(id);
        devices.touch(id);
    }

    @Override
    public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
        if (s.isOpen()) s.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        registry.unregister(deviceId(s), s);
    }

    private static String deviceId(WebSocketSession s) {
        return (String) s.getAttributes().get(DEVICE_ID);
    }
}
