package habitTracker.Project;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import habitTracker.integrations.trello.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProjectIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @SpyBean TrelloBoardGateway gateway;
    @MockBean TrelloApiClient trello;
    @SpyBean ProjectDeleteTokenService tokens;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(Project.class);
        mongoTemplate.dropCollection(User.class);
        reset(gateway, trello);
        mongoTemplate.dropCollection(BoardSnapshot.class);
        when(trello.createBoard(any(), any())).thenAnswer(i -> "tb-" + java.util.UUID.randomUUID());
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    private String create(UserPrincipal who, String name) throws Exception {
        String res = mockMvc.perform(post("/api/projects").with(auth.session(who)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private String token(UserPrincipal who, String id) throws Exception {
        String res = mockMvc.perform(post("/api/projects/" + id + "/delete-request").with(auth.session(who)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    @Test
    void crud() throws Exception {
        String id = create(alice, "P1");
        verify(trello).createBoard(eq(alice.getId()), eq("P1"));
        assertNotNull(mongoTemplate.findById(id, Project.class).getTrelloBoardId());

        mockMvc.perform(get("/api/projects").with(auth.session(alice))).andExpect(status().isOk());
        mockMvc.perform(put("/api/projects/" + id).with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P2\"}"))
                .andExpect(status().isOk());
        assertEquals("P2", mongoTemplate.findById(id, Project.class).getName());

        mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"L\",\"trelloBoardId\":\"abc\"}"))
                .andExpect(status().isCreated());
        verify(trello).getBoard(alice.getId(), "abc");
        assertTrue(mongoTemplate.findAll(Project.class).stream().anyMatch(p -> "abc".equals(p.getTrelloBoardId()) && p.isImported()));

        mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userIsolation() throws Exception {
        String id = create(alice, "A");
        String tok = token(alice, id);

        mockMvc.perform(get("/api/projects/" + id).with(auth.session(bob))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/projects/" + id).with(auth.session(bob)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/" + id + "/delete-request").with(auth.session(bob)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(bob)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects").with(auth.session(bob)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        assertNotNull(mongoTemplate.findById(id, Project.class));
        verify(trello, never()).deleteBoard(any(), any());
    }

    @Test
    void deleteWithValidTokenRemovesProjectAndBoard() throws Exception {
        String id = create(alice, "A");
        String boardId = mongoTemplate.findById(id, Project.class).getTrelloBoardId();
        String tok = token(alice, id);
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isNoContent());
        assertNull(mongoTemplate.findById(id, Project.class));
        verify(trello).deleteBoard(alice.getId(), boardId);
    }

    @Test
    void deleteRejectsMissingWrongReusedAndCrossProjectTokens() throws Exception {
        String id = create(alice, "A");
        String other = create(alice, "B");

        mockMvc.perform(delete("/api/projects/" + id).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/" + id + "?token=nope").with(auth.session(alice)).with(csrf()))
                .andExpect(status().isForbidden());

        // token issued for another project
        String otherTok = token(alice, other);
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + otherTok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isForbidden());

        // reuse
        String tok = token(alice, id);
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isNotFound());
        assertNotNull(mongoTemplate.findById(other, Project.class));
    }

    @Test
    void expiredTokenRejected() throws Exception {
        String id = create(alice, "A");
        ProjectDeleteTokenService shortLived = new ProjectDeleteTokenService(1);
        String tok = shortLived.issue(id, alice.getId());
        Thread.sleep(20);
        assertFalse(shortLived.redeem(tok, id, alice.getId()));
        // and a fresh live token for a different user never matches
        String live = new ProjectDeleteTokenService().issue(id, alice.getId());
        assertFalse(tokens.redeem(live, id, alice.getId()));
        assertNotNull(mongoTemplate.findById(id, Project.class));
    }

    private Map<String, Object> rawBoard(String id, String name) {
        return Map.of("id", id, "name", name, "url", "http://t/" + id,
                "lists", List.of(Map.of("id", "l1", "name", "Todo", "pos", 1.0, "closed", false),
                        Map.of("id", "l2", "name", "Done", "pos", 2.0, "closed", false)),
                "cards", List.of(Map.of("id", "c1", "idList", "l1", "name", "a", "closed", false),
                        Map.of("id", "c2", "idList", "l2", "name", "b", "closed", false),
                        Map.of("id", "c3", "idList", "l2", "name", "gone", "closed", true)));
    }

    @Test
    void importSkipsAlreadyImportedAndStoresSnapshot() throws Exception {
        when(trello.getBoard(eq(alice.getId()), any())).thenAnswer(i -> rawBoard(i.getArgument(1), "Board " + i.getArgument(1)));
        when(trello.listMyBoards(alice.getId())).thenReturn(List.of(
                Map.of("id", "b1", "name", "One", "url", "u1"), Map.of("id", "b2", "name", "Two", "url", "u2")));
        String body = "{\"trelloBoardIds\":[\"b1\"]}";
        mockMvc.perform(post("/api/projects/import").with(auth.session(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].name").value("Board b1"));
        mockMvc.perform(post("/api/projects/import").with(auth.session(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"trelloBoardIds\":[\"b1\",\"b2\",\"b2\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].trelloBoardId").value("b2"));
        assertEquals(2, mongoTemplate.findAll(Project.class).size());
        assertEquals(2, mongoTemplate.findAll(BoardSnapshot.class).size());

        mockMvc.perform(get("/api/projects/trello/boards").with(auth.session(alice)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].imported").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].imported").value(true));
        // bob has linked nothing
        when(trello.listMyBoards(bob.getId())).thenReturn(List.of(Map.of("id", "b1", "name", "One", "url", "u1")));
        mockMvc.perform(get("/api/projects/trello/boards").with(auth.session(bob)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].imported").value(false));

        // list shows counts from the snapshot (closed card excluded; "Done" list cards are done)
        mockMvc.perform(get("/api/projects").with(auth.session(alice)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].cardCount").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].doneCount").value(1));
    }

    @Test
    void deletingImportedProjectKeepsTrelloBoard() throws Exception {
        when(trello.getBoard(any(), any())).thenReturn(rawBoard("b1", "One"));
        mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"L\",\"trelloBoardId\":\"b1\"}"))
                .andExpect(status().isCreated());
        String id = mongoTemplate.findAll(Project.class).get(0).getId();
        String tok = token(alice, id);
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isNoContent());
        assertNull(mongoTemplate.findById(id, Project.class));
        assertEquals(0, mongoTemplate.findAll(BoardSnapshot.class).size());
        verify(trello, never()).deleteBoard(any(), any());
    }

    @Test
    void boardServesCacheThenRefreshesAndFallsBackStale() throws Exception {
        when(trello.getBoard(any(), any())).thenReturn(rawBoard("b1", "One"));
        mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"L\",\"trelloBoardId\":\"b1\"}"));
        String id = mongoTemplate.findAll(Project.class).get(0).getId();
        clearInvocations(trello);

        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.boardId").value("b1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.cards.length()").value(3));
        verify(trello, never()).getBoard(any(), any()); // fresh cache

        mockMvc.perform(get("/api/projects/" + id + "/board?refresh=true").with(auth.session(alice))).andExpect(status().isOk());
        verify(trello, times(1)).getBoard(any(), any());

        // expire the cache, Trello down -> stale copy with header
        BoardSnapshot s = mongoTemplate.findAll(BoardSnapshot.class).get(0);
        s.setSyncedAt(Instant.now().minusSeconds(120));
        mongoTemplate.save(s);
        doThrow(new TrelloGatewayException("down", false, null)).when(trello).getBoard(any(), any());
        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Stale", "true"));

        // no snapshot + Trello down -> 502
        mongoTemplate.dropCollection(BoardSnapshot.class);
        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(alice))).andExpect(status().isBadGateway());
    }

    @Test
    void boardErrorsAndIsolation() throws Exception {
        String id = create(alice, "A");
        doThrow(new TrelloNotConnectedException()).when(trello).getBoard(any(), any());
        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(alice)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("trello_not_connected"));
        doThrow(new TrelloAuthException()).when(trello).getBoard(any(), any());
        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(alice)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("trello_token_invalid"));
        doThrow(new TrelloNotConnectedException()).when(trello).listMyBoards(any());
        mockMvc.perform(get("/api/projects/trello/boards").with(auth.session(alice))).andExpect(status().isPreconditionFailed());
        mockMvc.perform(get("/api/projects/" + id + "/board").with(auth.session(bob))).andExpect(status().isNotFound());
    }
}
