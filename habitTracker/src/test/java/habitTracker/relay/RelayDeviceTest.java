package habitTracker.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Browser side through MockMvc, runner side through a real WebSocket client on the random port;
 * both share the same UplinkRegistry bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "relay.proxy.timeout-ms=1500")
@AutoConfigureMockMvc
@Testcontainers
class RelayDeviceTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @LocalServerPort int port;
    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @Autowired UplinkRegistry registry;

    AuthTestHelper auth;
    final List<WebSocketSession> clients = new ArrayList<>();

    record Paired(String deviceId, String token) {}

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(User.class);
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), Device.class);
    }

    @AfterEach
    void closeClients() throws Exception {
        for (WebSocketSession s : clients) if (s.isOpen()) s.close();
    }

    // --- helpers ---

    static MockHttpSession verified(UserPrincipal who) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(TwoFactorService.VERIFIED_USER, who.getId());
        s.setAttribute(TwoFactorService.VERIFIED_UNTIL, Instant.now().getEpochSecond() + 3600);
        return s;
    }

    MockHttpServletRequestBuilder as(UserPrincipal who, MockHttpServletRequestBuilder b) {
        return b.session(verified(who)).with(auth.session(who)).with(csrf());
    }

    String newCode(UserPrincipal who) throws Exception {
        String body = mockMvc.perform(as(who, post("/relay/api/devices/pairing-code")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asText();
    }

    String pairBody(String code) throws Exception {
        return json.writeValueAsString(Map.of("code", code, "name", "laptop"));
    }

    Paired pair(UserPrincipal who) throws Exception {
        String body = mockMvc.perform(post("/relay/pair").contentType(MediaType.APPLICATION_JSON)
                        .content(pairBody(newCode(who))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        return new Paired(n.get("deviceId").asText(), n.get("token").asText());
    }

    /** Fake runnerd: answers every req with a 201 echoing what it got (or stays silent). */
    WebSocketSession runner(String token, boolean answer, List<CloseStatus> closes) throws Exception {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Authorization", "Bearer " + token);
        WebSocketSession s = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage msg) throws Exception {
                if (!answer) return;
                JsonNode req = json.readTree(msg.getPayload());
                String echo = json.writeValueAsString(Map.of(
                        "method", req.get("method").asText(), "path", req.get("path").asText(),
                        "body", req.get("body").asText()));
                session.sendMessage(new TextMessage(json.writeValueAsString(Map.of(
                        "type", "res", "id", req.get("id").asText(), "status", 201,
                        "contentType", "application/json", "body", echo))));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                if (closes != null) closes.add(status);
            }
        }, h, URI.create("ws://localhost:" + port + "/relay/uplink")).get(5, TimeUnit.SECONDS);
        clients.add(s);
        return s;
    }

    static void await(BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 100 && !cond.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(cond.getAsBoolean(), "condition not reached in 5s");
    }

    // --- pairing ---

    @Test
    void pairingCodeIsSingleUseAndOnlyTheTokenHashIsStored() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        String code = newCode(alice);
        assertTrue(code.matches("[A-Z2-9]{8}"));

        String body = mockMvc.perform(post("/relay/pair").contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        String token = n.get("token").asText();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);

        Device d = mongoTemplate.findById(n.get("deviceId").asText(), Device.class);
        assertNotNull(d);
        assertEquals(alice.getId(), d.getUserId());
        assertEquals("laptop", d.getName());
        assertEquals(DeviceService.hash(token), d.getTokenHash());
        assertTrue(d.getTokenHash().matches("[0-9a-f]{64}"));
        assertFalse(mongoTemplate.getCollection("relay_devices").find().first().toJson().contains(token));

        mockMvc.perform(post("/relay/pair").contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/relay/pair").contentType(MediaType.APPLICATION_JSON).content(pairBody("ZZZZZZZZ")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/relay/pair").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relayApiNeedsLoginAndFreshTotp() throws Exception {
        mockMvc.perform(get("/relay/api/devices")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/relay/api/devices/pairing-code").with(csrf())).andExpect(status().isUnauthorized());

        UserPrincipal alice = auth.register("alice@example.com");
        mockMvc.perform(get("/relay/api/devices").with(auth.session(alice)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("two-factor verification required"));
        mockMvc.perform(post("/relay/api/devices/pairing-code").with(auth.session(alice)).with(csrf()))
                .andExpect(status().isUnauthorized());
        // Pages keep redirecting to the TOTP form.
        mockMvc.perform(get("/relay").with(auth.session(alice))).andExpect(status().is3xxRedirection());
    }

    // --- ownership ---

    @Test
    void otherUsersDevicesAreInvisible() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        UserPrincipal bob = auth.register("bob@example.com");
        Paired dev = pair(alice);

        mockMvc.perform(as(bob, get("/relay/api/devices"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(bob, get("/relay/api/devices/" + dev.deviceId() + "/v1/projects")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(bob, delete("/relay/api/devices/" + dev.deviceId()))).andExpect(status().isNotFound());

        assertNotNull(mongoTemplate.findById(dev.deviceId(), Device.class));
        mockMvc.perform(as(alice, get("/relay/api/devices"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dev.deviceId()))
                .andExpect(jsonPath("$[0].name").value("laptop"))
                .andExpect(jsonPath("$[0].online").value(false));
    }

    // --- uplink + proxy ---

    @Test
    void unknownTokenIsRefusedBeforeUpgrade() {
        ExecutionException e = assertThrows(ExecutionException.class, () -> runner("not-a-token", true, null));
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) chain.append(t.getMessage()).append(" | ");
        assertTrue(chain.toString().contains("401"), chain.toString());
    }

    @Test
    void proxiesThroughTheUplinkAndRevokeClosesIt() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Paired dev = pair(alice);
        List<CloseStatus> closes = new CopyOnWriteArrayList<>();
        WebSocketSession ws = runner(dev.token(), true, closes);
        await(() -> registry.isOnline(dev.deviceId()));

        String out = mockMvc.perform(as(alice, post("/relay/api/devices/" + dev.deviceId() + "/v1/sessions/abc/message?x=1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", "application/json"))
                .andReturn().getResponse().getContentAsString();
        JsonNode echo = json.readTree(out);
        assertEquals("POST", echo.get("method").asText());
        assertEquals("/v1/sessions/abc/message?x=1", echo.get("path").asText());
        assertEquals("{\"text\":\"hi\"}", echo.get("body").asText());

        mockMvc.perform(as(alice, get("/relay/api/devices"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].lastSeenAt").isNotEmpty());

        mockMvc.perform(as(alice, delete("/relay/api/devices/" + dev.deviceId()))).andExpect(status().isNoContent());
        await(() -> !ws.isOpen());
        assertNull(mongoTemplate.findById(dev.deviceId(), Device.class));
        assertFalse(registry.isOnline(dev.deviceId()));
        // A revoked token can't come back.
        assertThrows(ExecutionException.class, () -> runner(dev.token(), true, null));
    }

    @Test
    void newConnectionReplacesOld() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Paired dev = pair(alice);
        List<CloseStatus> closes = new CopyOnWriteArrayList<>();
        WebSocketSession first = runner(dev.token(), true, closes);
        await(() -> registry.isOnline(dev.deviceId()));
        runner(dev.token(), true, null);
        await(() -> !first.isOpen());
        assertEquals(UplinkRegistry.REPLACED.getCode(), closes.get(0).getCode());
        await(() -> registry.isOnline(dev.deviceId()));
        mockMvc.perform(as(alice, get("/relay/api/devices/" + dev.deviceId() + "/v1/projects")))
                .andExpect(status().isCreated());
    }

    @Test
    void offlineIs503AndSilentRunnerIs504() throws Exception {
        UserPrincipal alice = auth.register("alice@example.com");
        Paired dev = pair(alice);
        mockMvc.perform(as(alice, get("/relay/api/devices/" + dev.deviceId() + "/v1/projects")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("device offline"));

        runner(dev.token(), false, null);
        await(() -> registry.isOnline(dev.deviceId()));
        mockMvc.perform(as(alice, get("/relay/api/devices/" + dev.deviceId() + "/v1/projects")))
                .andExpect(status().isGatewayTimeout());
    }
}
