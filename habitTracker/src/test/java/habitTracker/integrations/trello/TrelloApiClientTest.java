package habitTracker.integrations.trello;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TrelloApiClientTest {

    RestTemplate rt = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(rt);
    TrelloCredentialRepository creds = mock(TrelloCredentialRepository.class);
    TrelloApiClient client = new TrelloApiClient(creds, rt, "http://trello/");

    @BeforeEach
    void setup() {
        when(creds.findByUserId("u")).thenReturn(Optional.of(TrelloCredential.builder().userId("u").apiKey("K").token("T").build()));
    }

    @Test
    void listMyBoardsUsesUserKeyAndToken() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/members/me/boards?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("filter", "open")).andExpect(queryParam("key", "K")).andExpect(queryParam("token", "T"))
                .andRespond(withSuccess("[{\"id\":\"b1\",\"name\":\"One\",\"url\":\"u\",\"closed\":false}]", MediaType.APPLICATION_JSON));
        List<Map<String, Object>> boards = client.listMyBoards("u");
        assertEquals("b1", boards.get(0).get("id"));
        server.verify();
    }

    @Test
    void getBoardRequestsNestedListsAndCards() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/b1?")))
                .andExpect(queryParam("cards", "all")).andExpect(queryParam("lists", "all"))
                .andExpect(queryParam("card_fields", org.hamcrest.Matchers.containsString("badges")))
                .andRespond(withSuccess("{\"id\":\"b1\",\"name\":\"One\",\"lists\":[],\"cards\":[]}", MediaType.APPLICATION_JSON));
        assertEquals("One", client.getBoard("u", "b1").get("name"));
    }

    @Test
    void createBoardEncodesNameAndSkipsDefaultLists() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("name", "A%20%26%20B")).andExpect(queryParam("defaultLists", "false"))
                .andRespond(withSuccess("{\"id\":\"new1\"}", MediaType.APPLICATION_JSON));
        assertEquals("new1", client.createBoard("u", "A & B"));
        server.verify();
    }

    @Test
    void deleteBoard() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/b1?"))).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.deleteBoard("u", "b1");
        server.verify();
    }

    @Test
    void noCredentialsThrowsNotConnectedWithoutCallingTrello() {
        when(creds.findByUserId("x")).thenReturn(Optional.empty());
        assertThrows(TrelloNotConnectedException.class, () -> client.listMyBoards("x"));
        when(creds.findByUserId("y")).thenReturn(Optional.of(TrelloCredential.builder().apiKey("K").token(" ").build()));
        assertThrows(TrelloNotConnectedException.class, () -> client.getBoard("y", "b"));
        server.verify();
    }

    @Test
    void unauthorizedAndOtherFailures() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/b1"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(TrelloAuthException.class, () -> client.getBoard("u", "b1"));
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/b2"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        TrelloGatewayException e = assertThrows(TrelloGatewayException.class, () -> client.getBoard("u", "b2"));
        assertEquals(404, e.getStatus());
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/b3"))).andRespond(withServerError());
        assertThrows(TrelloGatewayException.class, () -> client.getBoard("u", "b3"));
    }

    @Test
    void updateCardSendsOnlyGivenParamsAndNullDue() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/cards/c1?")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(queryParam("due", "null")).andExpect(queryParam("name", "A%20b"))
                .andExpect(requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("desc="))))
                .andExpect(queryParam("key", "K"))
                .andRespond(withSuccess("{\"id\":\"c1\"}", MediaType.APPLICATION_JSON));
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("name", "A b"); params.put("due", "null");
        assertEquals("c1", client.updateCard("u", "c1", params).get("id"));
        server.verify();
    }

    @Test
    void createCardAndListDefaultPos() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/lists?")))
                .andExpect(method(HttpMethod.POST)).andExpect(queryParam("idBoard", "b1")).andExpect(queryParam("pos", "bottom"))
                .andRespond(withSuccess("{\"id\":\"l1\"}", MediaType.APPLICATION_JSON));
        assertEquals("l1", client.createList("u", "b1", "Todo", null).get("id"));
        server.verify();
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/cards/c1/idLabels?")))
                .andExpect(method(HttpMethod.POST)).andExpect(queryParam("value", "lab1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        client.addCardLabel("u", "c1", "lab1");
        server.verify();
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/cards/c1/idLabels/lab1?")))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.removeCardLabel("u", "c1", "lab1");
        server.verify();
    }

    @Test
    void rateLimitCarriesRetryAfter() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/cards/c1?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"));
        TrelloGatewayException e = assertThrows(TrelloGatewayException.class, () -> client.getCard("u", "c1"));
        assertEquals(429, e.getStatus());
        assertEquals("7", e.getRetryAfter());
    }

    @Test
    void moveListToBoardUsesIdBoardEndpoint() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/lists/L1/idBoard?")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(queryParam("value", "b2")).andExpect(queryParam("pos", "bottom"))
                .andExpect(queryParam("key", "K")).andExpect(queryParam("token", "T"))
                .andRespond(withSuccess("{\"id\":\"L1\",\"idBoard\":\"b2\",\"pos\":1.0}", MediaType.APPLICATION_JSON));
        assertEquals("b2", client.moveListToBoard("u", "L1", "b2", "bottom").get("idBoard"));
        server.verify();
    }

    @Test
    void unreachableMessageNamesRootCauseButNeverTheUri() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/boards/x?")))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("Read timed out"); });
        TrelloGatewayException e = assertThrows(TrelloGatewayException.class, () -> client.deleteBoard("u", "x"));
        assertTrue(e.getMessage().contains("SocketTimeoutException: Read timed out"), e.getMessage());
        assertTrue(e.getMessage().contains("DELETE /1/boards/x"), e.getMessage());
        assertFalse(e.getMessage().contains("token") || e.getMessage().contains("K&"), e.getMessage());
    }
}
