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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProjectAgentEndpointsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @SpyBean TrelloBoardGateway gateway;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(Project.class);
        mongoTemplate.dropCollection(User.class);
        reset(gateway);
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    private String create(UserPrincipal who) throws Exception {
        String res = mockMvc.perform(post("/api/projects").with(auth.session(who)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions plan(UserPrincipal who, String id, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + id + "/plan").with(auth.session(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void planPassesThroughAndUsesProjectBoard() throws Exception {
        String id = create(alice);
        String boardId = mongoTemplate.findById(id, Project.class).getTrelloBoardId();
        doReturn(Map.of("cards", java.util.List.of("c"), "proposal", Map.of("k", 1)))
                .when(gateway).plan(eq(boardId), eq("ship it"));
        plan(alice, id, "{\"description\":\"ship it\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cards[0]").value("c"))
                .andExpect(jsonPath("$.proposal.k").value(1));
    }

    @Test
    void applyPassesBody() throws Exception {
        String id = create(alice);
        String boardId = mongoTemplate.findById(id, Project.class).getTrelloBoardId();
        doReturn(Map.of("done", true)).when(gateway).apply(eq(boardId), anyMap());
        mockMvc.perform(post("/api/projects/" + id + "/apply").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pace\":\"slow\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        verify(gateway).apply(eq(boardId), argThat(m -> "slow".equals(m.get("pace"))));
    }

    @Test
    void notOwnedIs404AndGatewayUntouched() throws Exception {
        String id = create(alice);
        plan(bob, id, "{\"description\":\"x\"}").andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/" + id + "/apply").with(auth.session(bob)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verify(gateway, never()).plan(any(), any());
        verify(gateway, never()).apply(any(), any());
    }

    @Test
    void blankDescriptionIs400() throws Exception {
        String id = create(alice);
        plan(alice, id, "{\"description\":\" \"}").andExpect(status().isBadRequest());
    }

    @Test
    void upstream409Maps409AndOtherMaps502() throws Exception {
        String id = create(alice);
        doThrow(new TrelloGatewayException("busy", true, null)).when(gateway).plan(any(), any());
        plan(alice, id, "{\"description\":\"x\"}").andExpect(status().isConflict());
        doThrow(new TrelloGatewayException("down", false, null)).when(gateway).plan(any(), any());
        plan(alice, id, "{\"description\":\"x\"}").andExpect(status().isBadGateway());
    }
}
