package habitTracker.aiusage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.Project.Project;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AiUsageEndpointsTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper json;
    @Autowired AiUsageService service;

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.remove(new Query(), AiUsage.class);
        mongoTemplate.remove(new Query(), Project.class);
        mongoTemplate.dropCollection(User.class);
        alice = auth.register("alice@test.com");
        bob = auth.register("bob@test.com");
    }

    private JsonNode snap(String state, String usage) throws Exception {
        return json.readTree("{\"state\":\"" + state + "\"" + (usage == null ? "" : ",\"usage\":" + usage) + "}");
    }

    @Test
    void recordsOncePerFinishedJob_onlyWithUsage() throws Exception {
        String u = alice.getId();
        service.record(u, "j1", snap("running", "{\"inputTokens\":5,\"outputTokens\":1}"), AiUsageService.Source.map("p"));
        service.record(u, "j2", snap("done", null), AiUsageService.Source.map("p"));
        assertEquals(0, mongoTemplate.count(new Query(), AiUsage.class));

        service.record(u, "j1", snap("done", "{\"inputTokens\":5,\"outputTokens\":1}"), AiUsageService.Source.map("p"));
        service.record(u, "j1", snap("done", "{\"inputTokens\":5,\"outputTokens\":1}"), AiUsageService.Source.map("p"));
        service.record(u, "j3", snap("failed", "{\"input_tokens\":2,\"cache_read_input_tokens\":8,\"output_tokens\":1}"),
                AiUsageService.Source.kpi(AiUsage.KPI_REPAIR, "Steps"));
        assertEquals(2, mongoTemplate.count(new Query(), AiUsage.class));
    }

    @Test
    void summaryIsPerUser_groupedAndNamed() throws Exception {
        Project p = mongoTemplate.save(Project.builder().name("Shop").userId(alice.getId()).build());
        service.record(alice.getId(), "m1", snap("done", "{\"inputTokens\":1000,\"outputTokens\":200}"), AiUsageService.Source.map(p.getId()));
        service.record(alice.getId(), "p1", snap("done", "{\"inputTokens\":300,\"outputTokens\":100}"), AiUsageService.Source.plan(p.getId()));
        service.record(alice.getId(), "k1", snap("done", "{\"inputTokens\":50,\"outputTokens\":50}"),
                AiUsageService.Source.kpi(AiUsage.KPI_PROPOSE, "Steps"));
        service.record(bob.getId(), "b1", snap("done", "{\"inputTokens\":9999,\"outputTokens\":1}"), AiUsageService.Source.map("x"));

        String body = mockMvc.perform(get("/api/ai-usage?days=30").with(auth.session(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertEquals(1350, r.get("totals").get("input").asLong());
        assertEquals(350, r.get("totals").get("output").asLong());
        assertEquals("map", r.get("byKind").get(0).get("kind").asText());
        assertEquals(1, r.get("byProject").size());
        assertEquals("Shop", r.get("byProject").get(0).get("name").asText());
        assertEquals(1600, r.get("byProject").get(0).get("tokens").asLong());
        assertEquals("Steps", r.get("byKpi").get(0).get("kpiName").asText());
        assertEquals(3, r.get("recent").size());
        assertTrue(r.get("recent").findValuesAsText("label").contains("Steps"));
    }

    @Test
    void emptyForNewUser() throws Exception {
        String body = mockMvc.perform(get("/api/ai-usage").with(auth.session(bob)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertEquals(0, r.get("totals").get("input").asLong());
        assertEquals(0, r.get("recent").size());
    }
}
