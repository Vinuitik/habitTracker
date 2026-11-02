package habitTracker.Project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import habitTracker.integrations.trello.TrelloApiClient;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerJobService;
import habitTracker.relay.RunnerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PlannerEndpointsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @MockBean RunnerJobService runner;
    @MockBean TrelloApiClient trello;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        for (Class<?> c : List.of(Project.class, ProjectMap.class, PlannerConversation.class, BoardSnapshot.class)) {
            mongoTemplate.remove(new Query(), c);
        }
        mongoTemplate.dropCollection(User.class);
        reset(runner, trello);
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    private Project project(UserPrincipal who, boolean linked) {
        Project p = Project.builder().name("Shop").description("web shop").userId(who.getId())
                .trelloBoardId("board1").build();
        if (linked) {
            p.setRunnerDeviceId("d1");
            p.setRunnerProjectId("rp1");
            p.setFolderName("shop");
        }
        p = mongoTemplate.save(p);
        mongoTemplate.save(ProjectMap.builder().projectId(p.getId()).userId(who.getId())
                .index("# shop map").subsystems(new ArrayList<>(List.of(
                        new ProjectMap.Subsystem("Checkout", List.of("src/checkout"), "Files: Cart.java")))).build());
        BoardSnapshot.ListView todo = new BoardSnapshot.ListView();
        todo.setId("L1");
        todo.setName("Backlog");
        BoardSnapshot.CardView card = new BoardSnapshot.CardView();
        card.setId("C1");
        card.setIdList("L1");
        card.setName("Fix login redirect");
        mongoTemplate.save(BoardSnapshot.builder().projectId(p.getId()).userId(who.getId()).boardId("board1")
                .syncedAt(Instant.now()).lists(new ArrayList<>(List.of(todo))).cards(new ArrayList<>(List.of(card))).build());
        return p;
    }

    private ResultActions send(UserPrincipal who, String projectId, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + projectId + "/planner").with(auth.session(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode poll(UserPrincipal who, String projectId, String convId) throws Exception {
        String r = mockMvc.perform(get("/api/projects/" + projectId + "/planner/" + convId + "?after=0").with(auth.session(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(r);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode jobSnap(String state, String resultText) {
        var n = json.createObjectNode().put("state", state).put("nextSeq", 3);
        n.putArray("events").addObject().put("seq", 2).put("kind", "tool").put("text", "Read Cart.java");
        if (resultText != null) n.put("result", resultText);
        return n;
    }

    @Test
    void unlinkedProjectIs409() throws Exception {
        Project p = project(alice, false);
        send(alice, p.getId(), "{\"message\":\"plan checkout\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("project_not_linked"));
        verifyNoInteractions(runner);
    }

    @Test
    void clarifyThenDraftThenApply() throws Exception {
        Project p = project(alice, true);
        when(runner.startJob(any(), any(), any())).thenReturn("job1", "job2");

        JsonNode started = body(send(alice, p.getId(), "{\"message\":\"Add a checkout flow\"}").andExpect(status().isOk()));
        String conv = started.get("conversationId").asText();
        assertEquals("job1", started.get("jobId").asText());

        ArgumentCaptor<RunnerJobService.JobSpec> spec = ArgumentCaptor.forClass(RunnerJobService.JobSpec.class);
        verify(runner).startJob(eq(alice.getId()), eq("d1"), spec.capture());
        assertEquals("plan", spec.getValue().kind());
        assertEquals("rp1", spec.getValue().projectId());
        String prompt = spec.getValue().prompt();
        assertTrue(prompt.contains("# shop map"), "map index");
        assertTrue(prompt.contains("Files: Cart.java"), "subsystem notes");
        assertTrue(prompt.contains("Fix login redirect"), "board card");
        assertTrue(prompt.contains("Add a checkout flow"), "history");
        assertTrue(prompt.contains(LocalDate.now().toString()), "today");
        assertTrue(prompt.contains("\"questions\""), "clarify template");

        // running
        when(runner.pollJob(alice.getId(), "d1", "job1", 0)).thenReturn(jobSnap("running", null));
        JsonNode r = poll(alice, p.getId(), conv);
        assertEquals("running", r.get("state").asText());
        assertEquals("Read Cart.java", r.get("events").get(0).get("text").asText());
        assertEquals(3, r.get("nextSeq").asLong());

        // done, prose around the JSON
        when(runner.pollJob(alice.getId(), "d1", "job1", 0)).thenReturn(jobSnap("done",
                "Here you go:\n```json\n{\"reply\":\"Checkout touches Cart.\",\"questions\":[\"Card payments?\",\"Deadline?\"]}\n```"));
        r = poll(alice, p.getId(), conv);
        assertEquals("waiting_user", r.get("state").asText());
        assertEquals(2, r.get("questions").size());
        assertEquals(2, r.get("messages").size());
        assertEquals("ai", r.get("messages").get(1).get("role").asText());
        assertEquals("Checkout touches Cart.", r.get("messages").get(1).get("text").asText());

        // stored once: a second poll doesn't hit the runner or append again
        r = poll(alice, p.getId(), conv);
        assertEquals(2, r.get("messages").size());
        verify(runner, times(2)).pollJob(any(), any(), any(), anyLong());

        // lock → draft job
        JsonNode locked = body(mockMvc.perform(post("/api/projects/" + p.getId() + "/planner/" + conv + "/lock")
                .with(auth.session(alice)).with(csrf())).andExpect(status().isOk()));
        assertEquals("job2", locked.get("jobId").asText());
        verify(runner, times(2)).startJob(any(), any(), spec.capture());
        assertTrue(spec.getValue().prompt().contains("\"draft\""), "draft template");
        assertTrue(spec.getValue().prompt().contains("Card payments?"), "questions in history");

        String draftResult = "{\"reply\":\"Two lists.\",\"draft\":{\"lists\":["
                + "{\"name\":\"backlog\",\"cards\":[{\"title\":\"Add cart model\",\"desc\":\"Cart.java\",\"importance\":3}]},"
                + "{\"name\":\"Checkout\",\"cards\":[{\"title\":\"Pay button\",\"desc\":\"\",\"importance\":\"could\",\"due\":\"2026-12-01\"},"
                + "{\"title\":\"Receipt mail\",\"desc\":\"x\"}]}],"
                + "\"schedule\":[{\"date\":\"2026-11-10\",\"title\":\"Add cart model\",\"importance\":3},{\"date\":\"bad\",\"title\":\"Pay button\"}]}}";
        when(runner.pollJob(alice.getId(), "d1", "job2", 0)).thenReturn(jobSnap("done", draftResult));
        r = poll(alice, p.getId(), conv);
        assertEquals("drafted", r.get("state").asText());
        assertEquals(2, r.get("draft").get("lists").size());
        assertEquals(1, r.get("draft").get("schedule").size(), "invalid schedule row skipped");
        assertEquals(1, r.get("draft").get("lists").get(1).get("cards").get(0).get("importance").asInt());

        // apply: stored draft, deadline as fallback due
        when(trello.createList(any(), eq("board1"), eq("Checkout"), any())).thenReturn(Map.of("id", "L2"));
        when(trello.createCard(any(), any())).thenReturn(Map.of("id", "new"));
        mockMvc.perform(post("/api/projects/" + p.getId() + "/planner/" + conv + "/apply").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":\"2026-12-20\",\"pace\":\"steady\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(3));
        verify(trello, never()).createList(any(), any(), eq("backlog"), any());   // reuses "Backlog"
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> cards = ArgumentCaptor.forClass(Map.class);
        verify(trello, times(3)).createCard(eq(alice.getId()), cards.capture());
        List<Map<String, String>> all = cards.getAllValues();
        assertEquals("L1", all.get(0).get("idList"));
        assertEquals("2026-11-10T12:00:00.000Z", all.get(0).get("due"));
        assertTrue(all.get(0).get("desc").contains("```meta\nimportance: 3\n```"));
        assertEquals("L2", all.get(1).get("idList"));
        assertEquals("2026-12-01T12:00:00.000Z", all.get(1).get("due"));
        assertEquals("2026-12-20T12:00:00.000Z", all.get(2).get("due"));
    }

    @Test
    void applyUsesBodyDraftAndRejectsBadDraft() throws Exception {
        Project p = project(alice, true);
        PlannerConversation c = new PlannerConversation();
        c.setProjectId(p.getId());
        c.setUserId(alice.getId());
        c = mongoTemplate.save(c);
        String url = "/api/projects/" + p.getId() + "/planner/" + c.getId() + "/apply";
        mockMvc.perform(post(url).with(auth.session(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(auth.session(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"lists\":[{\"name\":\"\",\"cards\":[]}]}}"))
                .andExpect(status().isBadRequest());
        when(trello.createList(any(), any(), any(), any())).thenReturn(Map.of("id", "L9"));
        mockMvc.perform(post(url).with(auth.session(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"lists\":[{\"name\":\"New\",\"cards\":[{\"title\":\"T\",\"desc\":\"d\"}]}],\"schedule\":[]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        verify(runner, never()).startJob(any(), any(), any());
    }

    @Test
    void busyFailedAndIsolation() throws Exception {
        Project p = project(alice, true);
        when(runner.startJob(any(), any(), any())).thenReturn("job1", "job2");
        String conv = body(send(alice, p.getId(), "{\"message\":\"hi\"}")).get("conversationId").asText();

        // follow-up while running → 409
        send(alice, p.getId(), "{\"message\":\"more\",\"conversationId\":\"" + conv + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("planner_busy"));

        // other user → 404
        mockMvc.perform(get("/api/projects/" + p.getId() + "/planner/" + conv).with(auth.session(bob)))
                .andExpect(status().isNotFound());

        // job failed → failed; a follow-up is then allowed and resets to running
        var failed = json.createObjectNode().put("state", "failed").put("error", "boom").put("nextSeq", 1);
        failed.putArray("events");
        when(runner.pollJob(any(), any(), eq("job1"), anyLong())).thenReturn(failed);
        JsonNode r = poll(alice, p.getId(), conv);
        assertEquals("failed", r.get("state").asText());
        assertEquals("boom", r.get("error").asText());
        send(alice, p.getId(), "{\"message\":\"retry\",\"conversationId\":\"" + conv + "\"}").andExpect(status().isOk());
        when(runner.pollJob(any(), any(), eq("job2"), anyLong())).thenThrow(new RunnerCallException(404, "{}"));
        r = poll(alice, p.getId(), conv);
        assertEquals("failed", r.get("state").asText(), "job vanished on runner");
        assertEquals(2, r.get("messages").size(), "hi + retry; the busy message and failures add nothing");
    }

    @Test
    void runnerOfflineSavesNothing() throws Exception {
        Project p = project(alice, true);
        when(runner.startJob(any(), any(), any()))
                .thenThrow(new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "offline"));
        send(alice, p.getId(), "{\"message\":\"hi\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("runner_offline"));
        assertEquals(0, mongoTemplate.count(new Query(), PlannerConversation.class));
    }

    @Test
    void mapSectionDropsLeastRelevantNotesFirst() {
        String big = "x".repeat(5000);
        ProjectMap map = ProjectMap.builder().index("# idx").subsystems(new ArrayList<>(List.of(
                new ProjectMap.Subsystem("Billing", List.of("src/billing"), "Files: Invoice.java " + big),
                new ProjectMap.Subsystem("Search", List.of("src/search"), "Files: Index.java " + big)))).build();
        String s = PlannerPrompts.mapSection(map, "improve billing invoices", 7000);
        assertTrue(s.length() <= 7000);
        assertTrue(s.contains("Invoice.java"), "relevant notes kept");
        assertFalse(s.contains("Index.java"), "irrelevant notes dropped");
        assertTrue(s.contains("Subsystem: Search"), "name kept");
    }
}
