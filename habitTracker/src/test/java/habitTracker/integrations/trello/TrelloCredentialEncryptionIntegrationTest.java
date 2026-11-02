package habitTracker.integrations.trello;

import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import habitTracker.sync.VaultEncryptionService;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TrelloCredential.apiKey / token are stored encrypted, decrypted only for Trello API calls, and
// legacy raw rows are migrated exactly once.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TrelloCredentialEncryptionIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    static final String COLL = "trello_credentials";

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired VaultEncryptionService vault;
    @Autowired TrelloCredentialRepository repository;
    @Autowired TrelloCredentialSecretsMigration migration;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(TrelloCredential.class);
        mongoTemplate.dropCollection(habitTracker.auth.User.class);
    }

    private Document raw(String userId) {
        return mongoTemplate.findOne(new Query(Criteria.where("userId").is(userId)), Document.class, COLL);
    }

    @Test
    void savedCredential_isCiphertext_andApiCallsUsePlaintext() throws Exception {
        UserPrincipal alice = auth.register("alice-trello-enc@test.com");
        mockMvc.perform(put("/api/integrations/trello/credentials").with(auth.session(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"app-key-plain\",\"token\":\"user-token-plain\"}"))
                .andExpect(status().isOk());

        Document doc = raw(alice.getId());
        assertEquals(Boolean.TRUE, doc.getBoolean("secretsEncrypted"));
        assertFalse(doc.getString("apiKey").contains("app-key-plain"));
        assertFalse(doc.getString("token").contains("user-token-plain"));

        mockMvc.perform(get("/api/integrations/trello/credentials").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));

        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://trello/1/members/me/boards?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("key", "app-key-plain"))
                .andExpect(queryParam("token", "user-token-plain"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        new TrelloApiClient(repository, vault, rt, "http://trello/").listMyBoards(alice.getId());
        server.verify();
    }

    @Test
    void legacyRawRow_migratedOnce_andNotDoubleEncrypted() {
        mongoTemplate.insert(new Document("userId", "legacy-t").append("apiKey", "legacy-key").append("token", "legacy-token"), COLL);
        assertEquals("legacy-key", repository.findByUserId("legacy-t").orElseThrow().plainApiKey(vault));

        assertEquals(1, migration.migrate());
        Document first = raw("legacy-t");
        assertEquals(Boolean.TRUE, first.getBoolean("secretsEncrypted"));
        assertNotEquals("legacy-key", first.getString("apiKey"));
        assertNotEquals("legacy-token", first.getString("token"));
        TrelloCredential migrated = repository.findByUserId("legacy-t").orElseThrow();
        assertEquals("legacy-key", migrated.plainApiKey(vault));
        assertEquals("legacy-token", migrated.plainToken(vault));

        assertEquals(0, migration.migrate());
        Document second = raw("legacy-t");
        assertEquals(first.getString("apiKey"), second.getString("apiKey"));
        assertEquals(first.getString("token"), second.getString("token"));
        assertEquals("legacy-token", repository.findByUserId("legacy-t").orElseThrow().plainToken(vault));
    }
}
