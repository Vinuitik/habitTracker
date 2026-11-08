package habitTracker.relay;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HUB.md "KPI credentials": the hub never logs request or response bodies for /v1/credentials*. Drives a PUT with a
 * secret through the real proxy path (RelayProxyController → RunnerClient → UplinkRegistry → frame) with every
 * logger at TRACE and asserts the secret appears in no log event; it must still reach the runner frame intact.
 */
class RelayProxyCredentialLoggingTest {

    static final String SECRET = "ghp_SUPERSECRETtoken1234567890";
    static final ObjectMapper JSON = new ObjectMapper();

    ListAppender<ILoggingEvent> appender;
    Logger root;
    Level oldLevel;
    UplinkRegistry uplinks;
    RelayProxyController proxy;
    final List<String> sentFrames = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        oldLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        UserPrincipal me = mock(UserPrincipal.class);
        when(me.getId()).thenReturn("u1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(me, null, List.of()));

        DeviceService devices = mock(DeviceService.class);
        when(devices.owned("u1", "d1")).thenReturn(Optional.of(new Device()));
        uplinks = new UplinkRegistry(JSON);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        // The fake runner answers each "req" frame at once, echoing the request path (never the body) like the runner.
        doAnswer(inv -> {
            String frame = ((TextMessage) inv.getArgument(0)).getPayload();
            sentFrames.add(frame);
            JsonNode req = JSON.readTree(frame);
            String body = req.path("method").asText().equals("DELETE") ? ""
                    : "{\"name\":\"github\",\"host\":\"api.github.com\",\"auth\":\"bearer\",\"updatedAt\":\"2026-11-08T00:00:00Z\"}";
            uplinks.onFrame("d1", JSON.createObjectNode().put("type", "res").put("id", req.path("id").asText())
                    .put("status", req.path("method").asText().equals("DELETE") ? 204 : 200)
                    .put("contentType", "application/json").put("body", body).toString());
            return null;
        }).when(ws).sendMessage(any());
        uplinks.register("d1", ws);
        proxy = new RelayProxyController(devices, new RunnerClient(devices, uplinks, 2000));
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        root.setLevel(oldLevel);
        SecurityContextHolder.clearContext();
    }

    private ResponseEntity<byte[]> call(String method, String path, String body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/relay/api/devices/d1" + path);
        if (body != null) {
            req.setContent(body.getBytes(UTF_8));
            req.setContentType("application/json");
        }
        return proxy.proxy("d1", req);
    }

    @Test
    void credentialPut_reachesTheRunner_butNeverTheLogs() throws Exception {
        String body = "{\"host\":\"api.github.com\",\"auth\":\"bearer\",\"secret\":\"" + SECRET + "\"}";
        ResponseEntity<byte[]> r = call("PUT", "/v1/credentials/github", body);
        assertEquals(200, r.getStatusCode().value());
        assertFalse(new String(r.getBody(), UTF_8).contains(SECRET));
        call("GET", "/v1/credentials", null);
        call("DELETE", "/v1/credentials/github", null);

        assertEquals(3, sentFrames.size());
        assertEquals(body, JSON.readTree(sentFrames.get(0)).path("body").asText()); // delivered verbatim
        for (ILoggingEvent e : appender.list) {
            assertFalse(e.getFormattedMessage().contains(SECRET), "secret logged: " + e.getFormattedMessage());
            if (e.getThrowableProxy() != null) assertFalse(e.getThrowableProxy().getMessage() != null
                    && e.getThrowableProxy().getMessage().contains(SECRET));
        }
    }

    @Test
    void badFrameFromRunner_logsNoFrameContent() {
        uplinks.onFrame("d1", "{\"type\":\"res\",\"body\":\"" + SECRET + "\" garbage " + SECRET);
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("bad frame")));
        for (ILoggingEvent e : appender.list) assertFalse(e.getFormattedMessage().contains(SECRET), e.getFormattedMessage());
    }
}
