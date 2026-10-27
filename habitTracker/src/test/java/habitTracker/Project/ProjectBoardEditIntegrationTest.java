package habitTracker.Project;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import habitTracker.integrations.trello.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProjectBoardEditIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @MockBean TrelloApiClient trello;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;
    String pid;

    static Map<String, Object> card(String id, String idList, String idBoard, List<Map<String, Object>> labels) {
        Map<String, Object> c = new HashMap<>();
        c.put("id", id); c.put("idList", idList); c.put("idBoard", idBoard); c.put("name", "n-" + id);
        c.put("labels", labels); c.put("pos", 1.0);
        return c;
    }

    static Map<String, Object> list(String id, String name) {
        return new HashMap<>(Map.of("id", id, "name", name, "pos", 1.0, "closed", false, "idBoard", "b1"));
    }

    static Map<String, Object> foreignList() {
        Map<String, Object> l = list("LX", "x");
        l.put("idBoard", "other");
        return l;
    }

    Map<String, Object> board(boolean withCompleted) {
        List<Map<String, Object>> lists = new ArrayList<>(List.of(list("L1", "To do")));
        if (withCompleted) lists.add(list("L2", "Completed"));
        return new HashMap<>(Map.of("id", "b1", "name", "Board", "url", "u", "lists", lists,
                "cards", List.of(card("c1", "L1", "b1", List.of()))));
    }

    void project(boolean withCompleted) throws Exception {
        when(trello.getBoard(alice.getId(), "b1")).thenReturn(board(withCompleted));
        String res = mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P\",\"trelloBoardId\":\"b1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        pid = json.readTree(res).get("id").asText();
    }

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(Project.class);
        mongoTemplate.dropCollection(User.class);
        mongoTemplate.dropCollection(BoardSnapshot.class);
        reset(trello);
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, UserPrincipal who, String body) throws Exception {
        b.with(auth.session(who)).with(csrf());
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(b);
    }

    @Test
    void createCardPatchesSnapshotWithoutRefetch() throws Exception {
        project(false);
        when(trello.createCard(eq(alice.getId()), anyMap())).thenReturn(card("c2", "L1", "b1", List.of()));
        send(post("/api/projects/" + pid + "/cards"), alice, "{\"idList\":\"L1\",\"name\":\"New\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("c2"));
        verify(trello).createCard(eq(alice.getId()), argThat(m -> "bottom".equals(m.get("pos")) && "New".equals(m.get("name")) && !m.containsKey("desc")));
        send(get("/api/projects/" + pid + "/board"), alice, null)
                .andExpect(jsonPath("$.cards.length()").value(2));
        verify(trello, times(1)).getBoard(any(), any());
    }

    @Test
    void createListAndValidation() throws Exception {
        project(false);
        when(trello.createList(alice.getId(), "b1", "Doing", null)).thenReturn(list("L9", "Doing"));
        send(post("/api/projects/" + pid + "/lists"), alice, "{\"name\":\"Doing\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("L9"));
        send(get("/api/projects/" + pid + "/board"), alice, null).andExpect(jsonPath("$.lists.length()").value(2));
        send(post("/api/projects/" + pid + "/lists"), alice, "{\"name\":\"  \"}").andExpect(status().isBadRequest());
        send(post("/api/projects/" + pid + "/cards"), alice, "{\"idList\":\"L1\",\"name\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void patchSendsOnlyProvidedFieldsAndExplicitNullDue() throws Exception {
        project(false);
        when(trello.updateCard(eq(alice.getId()), eq("c1"), anyMap())).thenReturn(card("c1", "L1", "b1", List.of()));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"due\":null}").andExpect(status().isOk());
        verify(trello).updateCard(alice.getId(), "c1", Map.of("due", "null"));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"X\",\"pos\":\"top\",\"dueComplete\":true}").andExpect(status().isOk());
        verify(trello).updateCard(alice.getId(), "c1", Map.of("name", "X", "pos", "top", "dueComplete", "true"));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void completeAddsLabelAndMovesOnlyWhenCompletedListExists() throws Exception {
        project(true);
        Map<String, Object> label = Map.of("id", "lab1", "name", "done", "color", "green");
        when(trello.getCard(alice.getId(), "c1")).thenReturn(card("c1", "L1", "b1", List.of()))
                .thenReturn(card("c1", "L2", "b1", List.of(label)));
        when(trello.getBoardLabels(alice.getId(), "b1")).thenReturn(List.of());
        when(trello.createLabel(alice.getId(), "b1", "done", "green")).thenReturn(Map.of("id", "lab1"));
        send(post("/api/projects/" + pid + "/cards/c1/complete"), alice, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true)).andExpect(jsonPath("$.idList").value("L2"));
        verify(trello).addCardLabel(alice.getId(), "c1", "lab1");
        verify(trello).updateCard(alice.getId(), "c1", Map.of("idList", "L2", "pos", "top"));

        // reopen: label removed, no move
        reset(trello);
        when(trello.getCard(alice.getId(), "c1")).thenReturn(card("c1", "L2", "b1", List.of(label)))
                .thenReturn(card("c1", "L2", "b1", List.of()));
        send(post("/api/projects/" + pid + "/cards/c1/reopen"), alice, null).andExpect(status().isOk());
        verify(trello).removeCardLabel(alice.getId(), "c1", "lab1");
        verify(trello, never()).updateCard(any(), any(), anyMap());
    }

    @Test
    void completeWithoutCompletedListDoesNotMoveOrCreateLists() throws Exception {
        project(false);
        Map<String, Object> label = Map.of("id", "lab1", "name", "Done", "color", "green");
        when(trello.getCard(alice.getId(), "c1")).thenReturn(card("c1", "L1", "b1", List.of()))
                .thenReturn(card("c1", "L1", "b1", List.of(label)));
        when(trello.getBoardLabels(alice.getId(), "b1")).thenReturn(List.of(label));
        send(post("/api/projects/" + pid + "/cards/c1/complete"), alice, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        verify(trello).addCardLabel(alice.getId(), "c1", "lab1");
        verify(trello, never()).createLabel(any(), any(), any(), any());
        verify(trello, never()).updateCard(any(), any(), anyMap());
        verify(trello, never()).createList(any(), any(), any(), any());
    }

    @Test
    void crossBoardIdsAndCrossUserProjectAre404() throws Exception {
        project(false);
        when(trello.getCard(alice.getId(), "foreign")).thenReturn(card("foreign", "LX", "other", List.of()));
        when(trello.getList(alice.getId(), "LX")).thenReturn(foreignList());
        send(patch("/api/projects/" + pid + "/cards/foreign"), alice, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        send(post("/api/projects/" + pid + "/cards/foreign/complete"), alice, null).andExpect(status().isNotFound());
        send(post("/api/projects/" + pid + "/cards"), alice, "{\"idList\":\"LX\",\"name\":\"x\"}").andExpect(status().isNotFound());
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"idList\":\"LX\"}").andExpect(status().isNotFound());
        verify(trello, never()).updateCard(any(), any(), anyMap());
        verify(trello, never()).createCard(any(), anyMap());

        send(post("/api/projects/" + pid + "/cards"), bob, "{\"idList\":\"L1\",\"name\":\"x\"}").andExpect(status().isNotFound());
        send(post("/api/projects/" + pid + "/lists"), bob, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        send(patch("/api/projects/" + pid + "/cards/c1"), bob, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        verify(trello, never()).createList(any(), any(), any(), any());
    }

    @Test
    void trelloErrorsMap() throws Exception {
        project(false);
        when(trello.updateCard(any(), eq("c1"), anyMap())).thenThrow(new TrelloNotConnectedException());
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"x\"}")
                .andExpect(status().is(412)).andExpect(jsonPath("$.error").value("trello_not_connected"));
        when(trello.updateCard(any(), eq("c1"), anyMap())).thenThrow(new TrelloAuthException());
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"x\"}")
                .andExpect(status().is(412)).andExpect(jsonPath("$.error").value("trello_token_invalid"));
        when(trello.updateCard(any(), eq("c1"), anyMap())).thenThrow(new TrelloGatewayException("x", false, null, 404));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        when(trello.updateCard(any(), eq("c1"), anyMap())).thenThrow(new TrelloGatewayException("x", false, null, 429, "9"));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"x\"}")
                .andExpect(status().is(429)).andExpect(header().string("Retry-After", "9"))
                .andExpect(jsonPath("$.error").value("trello_rate_limited"));
        when(trello.updateCard(any(), eq("c1"), anyMap())).thenThrow(new TrelloGatewayException("x", false, null, 500));
        send(patch("/api/projects/" + pid + "/cards/c1"), alice, "{\"name\":\"x\"}").andExpect(status().is(502));
    }
}
