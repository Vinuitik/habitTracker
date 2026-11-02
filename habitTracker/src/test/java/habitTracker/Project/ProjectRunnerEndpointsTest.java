package habitTracker.Project;

import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import habitTracker.relay.RunnerClient;
import habitTracker.relay.RunnerUnavailableException;
import habitTracker.relay.UplinkRegistry;
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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProjectRunnerEndpointsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @MockBean RunnerClient runner;
    @MockBean habitTracker.integrations.trello.TrelloApiClient trello;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    static final String MAP_JSON = "{\"commitSha\":\"abc123\",\"index\":\"# repo map\","
            + "\"subsystems\":[{\"name\":\"Backend\",\"paths\":[\"src/main\"],\"notes\":\"Files: A.java\"}]}";

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(Project.class);
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), ProjectMap.class);
        mongoTemplate.dropCollection(User.class);
        reset(runner);
        when(trello.createBoard(any(), any())).thenAnswer(i -> "tb-" + java.util.UUID.randomUUID());
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    private static UplinkRegistry.Response res(int status, String body) {
        return new UplinkRegistry.Response(status, "application/json", body);
    }

    private String create(UserPrincipal who) throws Exception {
        String r = mockMvc.perform(post("/api/projects").with(auth.session(who)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("id").asText();
    }

    private ResultActions link(UserPrincipal who, String id) throws Exception {
        return mockMvc.perform(post("/api/projects/" + id + "/link").with(auth.session(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"d1\",\"path\":\"/home/a/repo/\"}"));
    }

    private String linked(UserPrincipal who) throws Exception {
        String id = create(who);
        when(runner.call(any(), eq("d1"), eq("POST"), eq("/v1/projects"), any()))
                .thenReturn(res(201, "{\"id\":\"rp1\",\"name\":\"repo\",\"path\":\"/home/a/repo\"}"));
        link(who, id).andExpect(status().isOk());
        return id;
    }

    private String startMap(UserPrincipal who, String id) throws Exception {
        when(runner.call(any(), eq("d1"), eq("POST"), eq("/v1/jobs"), any())).thenReturn(res(202, "{\"jobId\":\"job-1\"}"));
        String r = mockMvc.perform(post("/api/projects/" + id + "/map").with(auth.session(who)).with(csrf()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("jobId").asText();
    }

    private ResultActions poll(UserPrincipal who, String id, String jobId) throws Exception {
        return mockMvc.perform(get("/api/projects/" + id + "/map/job/" + jobId + "?after=3").with(auth.session(who)));
    }

    @Test
    void linkRegistersFolderAndStoresFields() throws Exception {
        String id = create(alice);
        when(runner.call(any(), eq("d1"), eq("POST"), eq("/v1/projects"), any()))
                .thenReturn(res(201, "{\"id\":\"rp1\",\"name\":\"repo\",\"path\":\"/home/a/repo\"}"));
        link(alice, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerDeviceId").value("d1"))
                .andExpect(jsonPath("$.runnerProjectId").value("rp1"))
                .andExpect(jsonPath("$.folderName").value("repo"));
        verify(runner).call(eq(alice.getId()), eq("d1"), eq("POST"), eq("/v1/projects"),
                argThat(b -> b.contains("\"path\":\"/home/a/repo/\"")));
    }

    @Test
    void linkReusesExistingRegistrationOn400() throws Exception {
        String id = create(alice);
        when(runner.call(any(), eq("d1"), eq("POST"), eq("/v1/projects"), any()))
                .thenReturn(res(400, "{\"error\":\"already registered\"}"));
        when(runner.call(any(), eq("d1"), eq("GET"), eq("/v1/projects"), any()))
                .thenReturn(res(200, "[{\"id\":\"other\",\"path\":\"/x\"},{\"id\":\"rp7\",\"name\":\"repo\",\"path\":\"/home/a/repo\"}]"));
        link(alice, id).andExpect(status().isOk()).andExpect(jsonPath("$.runnerProjectId").value("rp7"));
    }

    @Test
    void linkBadPathPassesRunner400() throws Exception {
        String id = create(alice);
        when(runner.call(any(), eq("d1"), eq("POST"), eq("/v1/projects"), any()))
                .thenReturn(res(400, "{\"error\":\"not a directory\"}"));
        when(runner.call(any(), eq("d1"), eq("GET"), eq("/v1/projects"), any())).thenReturn(res(200, "[]"));
        link(alice, id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("runner_error"));
    }

    @Test
    void foreignDeviceAndOfflineRunnerAreTyped() throws Exception {
        String id = create(alice);
        when(runner.call(any(), any(), any(), any(), any())).thenThrow(
                new RunnerUnavailableException(RunnerUnavailableException.Reason.DEVICE_NOT_FOUND, "device not found"));
        link(alice, id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("runner_not_found"));
        reset(runner);
        when(runner.call(any(), any(), any(), any(), any())).thenThrow(
                new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "device offline"));
        link(alice, id).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("runner_offline"))
                .andExpect(jsonPath("$.message").value("Connect a runner"));
    }

    @Test
    void otherUsersProjectIs404AndRunnerUntouched() throws Exception {
        String id = create(alice);
        link(bob, id).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/" + id + "/map").with(auth.session(bob)).with(csrf()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(runner);
    }

    @Test
    void mapOnUnlinkedProjectIs409() throws Exception {
        String id = create(alice);
        mockMvc.perform(post("/api/projects/" + id + "/map").with(auth.session(alice)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_not_linked"));
    }

    @Test
    void mapJobStoresResultOnceAndSummaryServed() throws Exception {
        String id = linked(alice);
        mockMvc.perform(get("/api/projects/" + id + "/map").with(auth.session(alice))).andExpect(status().isNotFound());
        String jobId = startMap(alice, id);
        assertEquals("job-1", jobId);
        verify(runner).call(eq(alice.getId()), eq("d1"), eq("POST"), eq("/v1/jobs"),
                argThat(b -> b.contains("\"kind\":\"map\"") && b.contains("\"projectId\":\"rp1\"") && b.contains("subagent")));

        when(runner.call(any(), eq("d1"), eq("GET"), eq("/v1/jobs/job-1?after=3"), any()))
                .thenReturn(res(200, "{\"jobId\":\"job-1\",\"state\":\"running\",\"events\":[],\"nextSeq\":3}"));
        poll(alice, id, jobId).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.mapStored").doesNotExist());

        String done = json.createObjectNode().put("jobId", "job-1").put("state", "done").put("nextSeq", 9)
                .put("result", "```json\n" + MAP_JSON + "\n```").toString();
        when(runner.call(any(), eq("d1"), eq("GET"), eq("/v1/jobs/job-1?after=3"), any())).thenReturn(res(200, done));
        poll(alice, id, jobId).andExpect(status().isOk()).andExpect(jsonPath("$.mapStored").value(true));
        ProjectMap stored = mongoTemplate.findAll(ProjectMap.class).get(0);
        assertEquals("abc123", stored.getCommitSha());
        assertEquals("Files: A.java", stored.getSubsystems().get(0).getNotes());
        Instant builtAt = stored.getBuiltAt();

        poll(alice, id, jobId).andExpect(jsonPath("$.mapStored").value(true));
        assertEquals(1, mongoTemplate.findAll(ProjectMap.class).size());
        assertEquals(builtAt, mongoTemplate.findAll(ProjectMap.class).get(0).getBuiltAt());

        mockMvc.perform(get("/api/projects/" + id + "/map").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value("abc123"))
                .andExpect(jsonPath("$.subsystems[0].name").value("Backend"))
                .andExpect(jsonPath("$.subsystems[0].paths[0]").value("src/main"))
                .andExpect(jsonPath("$.subsystems[0].notes").doesNotExist());
        mockMvc.perform(get("/api/projects/" + id + "/map").with(auth.session(bob))).andExpect(status().isNotFound());
    }

    @Test
    void unusableResultReportsErrorAndStoresNothing() throws Exception {
        String id = linked(alice);
        String jobId = startMap(alice, id);
        when(runner.call(any(), eq("d1"), eq("GET"), any(), any()))
                .thenReturn(res(200, "{\"jobId\":\"job-1\",\"state\":\"done\",\"result\":\"sorry, no JSON\"}"));
        poll(alice, id, jobId).andExpect(jsonPath("$.mapStored").value(false))
                .andExpect(jsonPath("$.mapError").exists());
        assertTrue(mongoTemplate.findAll(ProjectMap.class).isEmpty());
    }

    @Test
    void pollOfUnknownJobIs404() throws Exception {
        String id = linked(alice);
        startMap(alice, id);
        poll(alice, id, "someone-elses").andExpect(status().isNotFound());
        verify(runner, never()).call(any(), any(), eq("GET"), startsWith("/v1/jobs/"), any());
    }

    @Test
    void unlinkClearsFields() throws Exception {
        String id = linked(alice);
        mockMvc.perform(delete("/api/projects/" + id + "/link").with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runnerDeviceId").doesNotExist());
        Project p = mongoTemplate.findById(id, Project.class);
        assertNull(p.getRunnerProjectId());
        assertNull(p.getFolderName());
    }
}
