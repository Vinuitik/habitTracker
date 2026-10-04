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
    @SpyBean ProjectDeleteTokenService tokens;

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
        verify(gateway).createBoard("P1");
        assertNotNull(mongoTemplate.findById(id, Project.class).getTrelloBoardId());

        mockMvc.perform(get("/api/projects").with(auth.session(alice))).andExpect(status().isOk());
        mockMvc.perform(put("/api/projects/" + id).with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P2\"}"))
                .andExpect(status().isOk());
        assertEquals("P2", mongoTemplate.findById(id, Project.class).getName());

        mockMvc.perform(post("/api/projects").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"L\",\"trelloBoardId\":\"abc\"}"))
                .andExpect(status().isCreated());
        verify(gateway).linkExisting("abc");

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
        verify(gateway, never()).deleteBoard(any());
    }

    @Test
    void deleteWithValidTokenRemovesProjectAndBoard() throws Exception {
        String id = create(alice, "A");
        String boardId = mongoTemplate.findById(id, Project.class).getTrelloBoardId();
        String tok = token(alice, id);
        mockMvc.perform(delete("/api/projects/" + id + "?token=" + tok).with(auth.session(alice)).with(csrf()))
                .andExpect(status().isNoContent());
        assertNull(mongoTemplate.findById(id, Project.class));
        verify(gateway).deleteBoard(boardId);
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
}
