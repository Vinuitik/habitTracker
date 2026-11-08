package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class KPITargetIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(User.class);
    }

    private JsonNode kpi(UserPrincipal u, String name) throws Exception {
        String body = mockMvc.perform(get("/api/kpis").with(auth.session(u)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode n : objectMapper.readTree(body)) if (name.equals(n.get("name").asText())) return n;
        fail("KPI " + name + " not listed");
        return null;
    }

    private void post(UserPrincipal u, String path, String json, int expected) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .with(auth.session(u)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected));
    }

    @Test
    void createTarget_thenFirstReadingBecomesBaseline_thenPutResetsTarget() throws Exception {
        UserPrincipal alice = auth.register("alice@test.com");
        String deadline = LocalDate.now().plusDays(30).toString();

        // goalType absent but targetValue present → TARGET
        post(alice, "/api/kpis/create",
                "{\"name\":\"Weight\",\"targetValue\":80,\"targetDeadline\":\"" + deadline + "\"}", 200);
        JsonNode k = kpi(alice, "Weight");
        assertEquals("TARGET", k.get("goalType").asText());
        assertEquals(80.0, k.get("target").get("value").asDouble());
        assertEquals(deadline, k.get("target").get("deadline").asText());
        assertTrue(k.get("target").path("baseline").isNull() || k.get("target").path("baseline").isMissingNode());
        assertEquals("NO_DATA", k.get("targetStatus").get("state").asText());

        // first reading fills baseline and infers direction
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/kpis/Weight/data")
                        .with(auth.session(alice)).with(csrf())
                        .param("date", LocalDate.now().toString()).param("value", "90"))
                .andExpect(status().isOk());
        k = kpi(alice, "Weight");
        assertEquals(90.0, k.get("target").get("baseline").asDouble());
        assertFalse(k.get("higherIsBetter").asBoolean());
        assertEquals("down", k.get("targetStatus").get("direction").asText());

        // PUT target: baseline = latest reading, setAt = today
        String newDeadline = LocalDate.now().plusDays(60).toString();
        String resp = mockMvc.perform(put("/api/kpis/Weight/target").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":100,\"deadline\":\"" + newDeadline + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode updated = objectMapper.readTree(resp);
        assertEquals(100.0, updated.get("target").get("value").asDouble());
        assertEquals(90.0, updated.get("target").get("baseline").asDouble());
        assertEquals(LocalDate.now().toString(), updated.get("target").get("setAt").asText());
        assertTrue(updated.get("higherIsBetter").asBoolean());
        assertEquals("up", updated.get("targetStatus").get("direction").asText());
    }

    @Test
    void createTarget_missingDeadline_is400() throws Exception {
        UserPrincipal alice = auth.register("alice2@test.com");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/kpis/create")
                        .with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Weight\",\"goalType\":\"TARGET\",\"targetValue\":80}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("targetDeadline")));
    }

    @Test
    void legacyCreate_withHigherIsBetterOnly_derivesGoalType() throws Exception {
        UserPrincipal alice = auth.register("alice3@test.com");
        post(alice, "/api/kpis/create", "{\"name\":\"Steps\",\"higherIsBetter\":true}", 200);
        JsonNode k = kpi(alice, "Steps");
        assertEquals("HIGHER", k.get("goalType").asText());
        assertTrue(k.path("targetStatus").isNull() || k.path("targetStatus").isMissingNode());
    }
}
