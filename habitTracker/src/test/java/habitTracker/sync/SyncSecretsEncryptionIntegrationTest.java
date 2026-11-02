package habitTracker.sync;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// UserSyncSettings.driveRefreshToken / encryptionKey are stored encrypted (VaultEncryptionService
// at-rest key), decrypted only at the points of use, and legacy raw rows are migrated exactly once.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SyncSecretsEncryptionIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    static final String COLL = "user_sync_settings";

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired VaultEncryptionService vault;
    @Autowired UserSyncSettingsRepository repository;
    @Autowired UserSyncSettingsSecretsMigration migration;
    @MockitoBean DriveService driveService;

    AuthTestHelper auth;

    @BeforeEach
    void setup() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(UserSyncSettings.class);
        mongoTemplate.dropCollection(habitTracker.auth.User.class);
    }

    private Document raw(String userId) {
        return mongoTemplate.findOne(new Query(Criteria.where("userId").is(userId)), Document.class, COLL);
    }

    @Test
    void connectFlow_storesCiphertext_andPointsOfUseGetPlaintext() throws Exception {
        UserPrincipal alice = auth.register("alice-secrets@test.com");
        when(driveService.isConfigured()).thenReturn(true);
        when(driveService.clientId()).thenReturn("client-id");
        when(driveService.exchangeCode(eq("auth-code"), anyString()))
                .thenReturn(new DriveService.TokenResponse("access-1", "refresh-plain-1", 3600L));
        when(driveService.findOrCreateFolder(eq("access-1"), anyString(), any())).thenReturn("folder-1");
        when(driveService.getAccessTokenWithExpiry("refresh-plain-1"))
                .thenReturn(new DriveService.TokenResponse("access-2", null, 3600L));

        // Real DriveOAuthService path: consent URL (mints state) -> callback (writes the row).
        MvcResult urlResult = mockMvc.perform(get("/api/sync/oauth/url").param("origin", "http://localhost")
                        .with(auth.session(alice)))
                .andExpect(status().isOk()).andReturn();
        String url = (String) objectMapper.readValue(urlResult.getResponse().getContentAsString(), Map.class).get("url");
        String state = url.replaceAll(".*[?&]state=([^&]+).*", "$1");
        mockMvc.perform(get("/api/sync/oauth/callback").param("code", "auth-code").param("state", state)
                        .with(auth.session(alice)))
                .andExpect(redirectedUrl("/connect-drive.html?drive=connected"));

        Document doc = raw(alice.getId());
        assertEquals(Boolean.TRUE, doc.getBoolean("secretsEncrypted"));
        assertFalse(doc.getString("driveRefreshToken").contains("refresh-plain-1"), "refresh token must not be stored raw");
        UserSyncSettings loaded = repository.findByUserId(alice.getId()).orElseThrow();
        String vaultKey = loaded.plainEncryptionKey(vault);
        assertEquals(32, Base64.getDecoder().decode(vaultKey).length);
        assertNotEquals(vaultKey, doc.getString("encryptionKey"), "vault key must not be stored raw");
        assertEquals("refresh-plain-1", loaded.plainDriveRefreshToken(vault));

        // /status refreshes with the PLAINTEXT refresh token and hands the plaintext vault key to the browser.
        mockMvc.perform(get("/api/sync/status").with(auth.session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.encryptionKey").value(vaultKey));
        verify(driveService).getAccessTokenWithExpiry("refresh-plain-1");

        // /pair hands the plaintext vault key to the companion.
        MvcResult gen = mockMvc.perform(post("/api/sync/generate-pairing-code").with(auth.session(alice)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String code = (String) objectMapper.readValue(gen.getResponse().getContentAsString(), Map.class).get("code");
        mockMvc.perform(post("/api/sync/pair").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryptionKey").value(vaultKey));
    }

    @Test
    void legacyRawRow_isReadableBeforeMigration_migratedOnce_andNotDoubleEncrypted() {
        String legacyKey = vault.generateKey();
        mongoTemplate.insert(new Document("userId", "legacy-1")
                .append("driveRefreshToken", "legacy-refresh")
                .append("mailboxFolderId", "mb-1")
                .append("encryptionKey", legacyKey), COLL);

        assertEquals("legacy-refresh", repository.findByUserId("legacy-1").orElseThrow().plainDriveRefreshToken(vault));

        assertEquals(1, migration.migrate());
        Document first = raw("legacy-1");
        assertEquals(Boolean.TRUE, first.getBoolean("secretsEncrypted"));
        assertNotEquals("legacy-refresh", first.getString("driveRefreshToken"));
        assertNotEquals(legacyKey, first.getString("encryptionKey"));
        assertEquals("mb-1", first.getString("mailboxFolderId"));
        UserSyncSettings migrated = repository.findByUserId("legacy-1").orElseThrow();
        assertEquals("legacy-refresh", migrated.plainDriveRefreshToken(vault));
        assertEquals(legacyKey, migrated.plainEncryptionKey(vault));

        assertEquals(0, migration.migrate());
        Document second = raw("legacy-1");
        assertEquals(first.getString("driveRefreshToken"), second.getString("driveRefreshToken"));
        assertEquals(first.getString("encryptionKey"), second.getString("encryptionKey"));
        assertEquals(legacyKey, repository.findByUserId("legacy-1").orElseThrow().plainEncryptionKey(vault));
    }
}
