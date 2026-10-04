package habitTracker.Project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpTrelloBoardGatewayTest {

    RestTemplate rt = new RestTemplate();
    MockRestServiceServer server;
    HttpTrelloBoardGateway gw;

    @BeforeEach
    void setup() {
        server = MockRestServiceServer.createServer(rt);
        gw = new HttpTrelloBoardGateway("http://mb:8092/", "tok", rt, rt);
    }

    @Test
    void blankTokenFailsFast() {
        assertThrows(IllegalStateException.class, () -> new HttpTrelloBoardGateway("http://x", " ", rt, rt));
    }

    @Test
    void createBoardSendsTokenAndReturnsId() {
        server.expect(requestTo("http://mb:8092/internal/boards")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "tok"))
                .andExpect(jsonPath("$.name").value("P"))
                .andRespond(withSuccess("{\"boardId\":\"b1\"}", MediaType.APPLICATION_JSON));
        assertEquals("b1", gw.createBoard("P"));
        server.verify();
    }

    @Test
    void deleteBoard() {
        server.expect(requestTo("http://mb:8092/internal/boards/b1")).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Internal-Token", "tok")).andRespond(withSuccess());
        gw.deleteBoard("b1");
        server.verify();
    }

    @Test
    void planPassesThroughBody() {
        server.expect(requestTo("http://mb:8092/internal/agent/plan"))
                .andExpect(jsonPath("$.boardId").value("b1"))
                .andExpect(jsonPath("$.description").value("do x"))
                .andRespond(withSuccess("{\"cards\":[1],\"proposal\":{\"a\":2}}", MediaType.APPLICATION_JSON));
        Map<String, Object> res = gw.plan("b1", "do x");
        assertTrue(res.containsKey("cards"));
        assertTrue(res.containsKey("proposal"));
    }

    @Test
    void applyForwardsOnlyDeadlineAndPace() {
        server.expect(requestTo("http://mb:8092/internal/agent/apply"))
                .andExpect(jsonPath("$.boardId").value("b1"))
                .andExpect(jsonPath("$.deadline").value("2026-12-01"))
                .andExpect(jsonPath("$.pace").value("fast"))
                .andExpect(jsonPath("$.evil").doesNotExist())
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        assertEquals(true, gw.apply("b1", Map.of("deadline", "2026-12-01", "pace", "fast", "evil", 1)).get("ok"));
    }

    @Test
    void upstream409IsConflict() {
        server.expect(requestTo("http://mb:8092/internal/agent/plan")).andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));
        TrelloGatewayException e = assertThrows(TrelloGatewayException.class, () -> gw.plan("b1", "d"));
        assertTrue(e.isConflict());
    }

    @Test
    void otherFailuresAreNotConflict() {
        server.expect(requestTo("http://mb:8092/internal/boards")).andRespond(withServerError());
        assertFalse(assertThrows(TrelloGatewayException.class, () -> gw.createBoard("P")).isConflict());
    }
}
