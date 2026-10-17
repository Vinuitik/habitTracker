package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Egress allowlist + scoped secrets: secret store CRUD and cross-user isolation, the gate accepting
 * declared hosts/secrets, "secret X not set" test-gate failure, and the sandbox payload carrying ONLY
 * the capability's declared hosts and secrets. LLM and sandbox sidecar are mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CapabilityEgressIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean CapabilityGenerator generator;
    @MockitoBean SandboxClient sandboxClient;

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilityRepository;
    @Autowired CapabilitySecretService secretService;
    @Autowired SandboxCapabilityProvider provider;
    final ObjectMapper mapper = new ObjectMapper();

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    static final Map<String, List<String>> GITHUB = Map.of(
            "network", List.of("api.github.com"), "secrets", List.of("GITHUB_TOKEN"));

    @BeforeEach
    void setUp() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        mongoTemplate.dropCollection(CapabilitySecret.class);
        mongoTemplate.dropCollection(User.class);
        reset(generator, sandboxClient);
        alice = auth.register("alice@example.com");
        bob = auth.register("bob@example.com");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode call(UserPrincipal u, String method, String path, String body, int expected) throws Exception {
        RequestPostProcessor user = auth.session(u);
        var req = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> post(path);
        };
        req = req.with(user).with(csrf());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String json = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.isEmpty() ? null : mapper.readTree(json);
    }

    private void setSecret(UserPrincipal u, String name, String value, int expected) throws Exception {
        call(u, "PUT", "/api/kpi-secrets/" + name, "{\"value\":\"" + value + "\"}", expected);
    }

    private KPI kpi(UserPrincipal u, String name) {
        return kpiRepository.save(KPI.builder().name(name).userId(u.getId()).active(true).higherIsBetter(true).build());
    }

    private static SandboxClient.Result ok(String stdout) {
        return new SandboxClient.Result(SandboxClient.Outcome.OK, 0, stdout, "");
    }

    private String proposeGithub(UserPrincipal u, String kpiName) throws Exception {
        when(generator.propose(anyString(), anyString()))
                .thenReturn(new CapabilityGenerator.Proposal("Count my open PRs", "SERVER", GITHUB));
        return call(u, "POST", "/api/kpis/" + kpiName + "/capability/propose", "{\"description\":\"open PRs\"}", 200)
                .get("id").asText();
    }

    // ── secret store ─────────────────────────────────────────────────────────

    @Test
    void secretCrud_neverReturnsValues_andIsEncryptedAtRest() throws Exception {
        setSecret(alice, "GITHUB_TOKEN", "ghp_supersecretvalue", 200);

        JsonNode list = call(alice, "GET", "/api/kpi-secrets", null, 200);
        assertEquals(List.of("GITHUB_TOKEN"), List.of(list.get("names").get(0).asText()));
        assertFalse(list.toString().contains("ghp_supersecretvalue"));

        Document raw = mongoTemplate.getCollection("kpi_capability_secrets").find().first();
        assertNotNull(raw);
        assertFalse(raw.toJson().contains("ghp_supersecretvalue"), "plaintext must not be stored");
        assertEquals(alice.getId(), raw.getString("userId"));

        assertEquals("ghp_supersecretvalue", secretService.resolve(alice.getId(), List.of("GITHUB_TOKEN")).get("GITHUB_TOKEN"));

        setSecret(alice, "GITHUB_TOKEN", "ghp_rotatedvalue", 200); // overwrite, still one doc
        assertEquals(1, mongoTemplate.getCollection("kpi_capability_secrets").countDocuments());
        assertEquals("ghp_rotatedvalue", secretService.resolve(alice.getId(), List.of("GITHUB_TOKEN")).get("GITHUB_TOKEN"));

        call(alice, "DELETE", "/api/kpi-secrets/GITHUB_TOKEN", null, 200);
        assertEquals(0, call(alice, "GET", "/api/kpi-secrets", null, 200).get("names").size());
    }

    @Test
    void secretSet_rejectsBadNamesAndValues() throws Exception {
        setSecret(alice, "HTTPS_PROXY", "abcdefgh", 400);
        setSecret(alice, "PATH", "abcdefgh", 400);
        setSecret(alice, "OK_NAME", "abc", 400);
        call(alice, "PUT", "/api/kpi-secrets/OK_NAME", "{\"value\":\"line1\\nline2\"}", 400);
        call(alice, "PUT", "/api/kpi-secrets/OK_NAME", "{}", 400);
        assertEquals(0, mongoTemplate.getCollection("kpi_capability_secrets").countDocuments());
    }

    @Test
    void secrets_areIsolatedBetweenUsers() throws Exception {
        setSecret(alice, "GITHUB_TOKEN", "alice-token-value", 200);

        // Bob can't see it, resolve it, overwrite it, or delete it.
        assertEquals(0, call(bob, "GET", "/api/kpi-secrets", null, 200).get("names").size());
        assertThrows(MissingSecretException.class, () -> secretService.resolve(bob.getId(), List.of("GITHUB_TOKEN")));
        setSecret(bob, "GITHUB_TOKEN", "bob-token-value-xx", 200);
        call(bob, "DELETE", "/api/kpi-secrets/GITHUB_TOKEN", null, 200);

        assertEquals(List.of("GITHUB_TOKEN"), secretService.listNames(alice.getId()));
        assertEquals("alice-token-value", secretService.resolve(alice.getId(), List.of("GITHUB_TOKEN")).get("GITHUB_TOKEN"));
        assertEquals(List.of(), secretService.listNames(bob.getId()));
    }

    @Test
    void secretEndpoints_requireAuthentication() throws Exception {
        int s = mockMvc.perform(get("/api/kpi-secrets")).andReturn().getResponse().getStatus();
        assertTrue(s == 302 || s == 401 || s == 403, "unexpected status " + s);
    }

    // ── gate + test gate ─────────────────────────────────────────────────────

    @Test
    void declaredHostsAndSecrets_areNowGrantable_andStoredNormalized() throws Exception {
        kpi(alice, "PRs");
        String id = proposeGithub(alice, "PRs");
        KPIProxyCapability stored = capabilityRepository.findById(id).orElseThrow();
        assertEquals(List.of("api.github.com"), stored.getDeclaredCapabilities().get("network"));
        assertEquals(List.of("GITHUB_TOKEN"), stored.getDeclaredCapabilities().get("secrets"));
    }

    @Test
    void approveWithUnsetSecret_failsTestGateWithClearReason_andNeverRunsOrGenerates() throws Exception {
        kpi(alice, "PRs");
        String id = proposeGithub(alice, "PRs");

        JsonNode res = call(alice, "POST", "/api/kpis/PRs/capability/" + id + "/approve", null, 200);

        assertEquals("TEST_FAILED", res.get("status").asText());
        assertTrue(res.get("failureReason").asText().contains("secret GITHUB_TOKEN not set"), res.toString());
        assertEquals(0, res.get("attempts").asInt());
        verifyNoInteractions(sandboxClient);
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void secretUnsetThenSet_regenerateGoesActive_withHappyPathPayload() throws Exception {
        KPI k = kpi(alice, "PRs");
        String id = proposeGithub(alice, "PRs");
        call(alice, "POST", "/api/kpis/PRs/capability/" + id + "/approve", null, 200); // fails: not set

        setSecret(alice, "GITHUB_TOKEN", "ghp_alice_value", 200);
        setSecret(alice, "UNRELATED_SECRET", "must-not-be-sent", 200);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print(3)");
        when(sandboxClient.run(any())).thenReturn(ok("3"));

        JsonNode res = call(alice, "POST", "/api/kpis/PRs/capability/" + id + "/regenerate", null, 200);

        assertEquals("ACTIVE", res.get("status").asText());
        ArgumentCaptor<SandboxClient.Request> cap = ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(cap.capture());
        SandboxClient.Request req = cap.getValue();
        assertEquals(List.of("api.github.com"), req.allowedHosts());
        assertEquals(Map.of("GITHUB_TOKEN", "ghp_alice_value"), req.secrets(), "only the declared secret");
        assertEquals(alice.getId(), req.userId());
        assertFalse(req.toString().contains("ghp_alice_value"), "Request.toString must not leak values");
        assertEquals(ProxyType.SANDBOX_CAPABILITY, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
        assertFalse(res.toString().contains("ghp_alice_value"));
    }

    @Test
    void secretsAreResolvedForTheCapabilityOwner_notAnotherUserWithTheSameSecretName() throws Exception {
        kpi(alice, "PRs");
        String id = proposeGithub(alice, "PRs");
        setSecret(bob, "GITHUB_TOKEN", "bobs-token-value", 200); // Bob has it, Alice does not

        JsonNode res = call(alice, "POST", "/api/kpis/PRs/capability/" + id + "/approve", null, 200);

        assertEquals("TEST_FAILED", res.get("status").asText());
        assertTrue(res.get("failureReason").asText().contains("GITHUB_TOKEN not set"));
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void capabilityWithNoDeclaredAccess_getsNoHostsAndNoSecrets_evenIfUserHasSecrets() throws Exception {
        kpi(alice, "Widgets");
        setSecret(alice, "GITHUB_TOKEN", "ghp_alice_value", 200);
        when(generator.propose(anyString(), anyString())).thenReturn(new CapabilityGenerator.Proposal(
                "Count", "SERVER", Map.of("network", List.of(), "secrets", List.of())));
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print(1)");
        when(sandboxClient.run(any())).thenReturn(ok("1"));
        String id = call(alice, "POST", "/api/kpis/Widgets/capability/propose", "{\"description\":\"x\"}", 200).get("id").asText();

        call(alice, "POST", "/api/kpis/Widgets/capability/" + id + "/approve", null, 200);

        ArgumentCaptor<SandboxClient.Request> cap = ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(cap.capture());
        assertTrue(cap.getValue().allowedHosts().isEmpty());
        assertTrue(cap.getValue().secrets().isEmpty());
    }

    // ── production runs (nightly provider) ───────────────────────────────────

    private KPI activeGithubKpi(UserPrincipal u) {
        KPI k = kpiRepository.save(KPI.builder().name("PRs").userId(u.getId()).active(true)
                .proxyType(ProxyType.SANDBOX_CAPABILITY).build());
        capabilityRepository.save(KPIProxyCapability.builder().userId(u.getId()).kpiId(k.getId())
                .sourceCode("print(4)").declaredCapabilities(GITHUB).status(CapabilityStatus.ACTIVE).build());
        return k;
    }

    @Test
    void nightlyRun_sendsDeclaredHostsAndSecrets_andParsesValue() {
        KPI k = activeGithubKpi(alice);
        secretService.set(alice.getId(), "GITHUB_TOKEN", "ghp_alice_value");
        secretService.set(alice.getId(), "OTHER", "not-declared-value");
        when(sandboxClient.run(any())).thenReturn(ok("4"));

        Optional<Double> v = provider.fetchValue(k, LocalDate.now().minusDays(1));

        assertEquals(Optional.of(4.0), v);
        ArgumentCaptor<SandboxClient.Request> cap = ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(cap.capture());
        assertEquals(List.of("api.github.com"), cap.getValue().allowedHosts());
        assertEquals(Map.of("GITHUB_TOKEN", "ghp_alice_value"), cap.getValue().secrets());
    }

    @Test
    void nightlyRun_withDeletedSecret_failsWithoutRunning_andRecordsReason() {
        KPI k = activeGithubKpi(alice);

        assertTrue(provider.fetchValue(k, LocalDate.now().minusDays(1)).isEmpty());

        verifyNoInteractions(sandboxClient);
        KPIProxyCapability c = capabilityRepository.findFirstByKpiIdAndUserIdOrderByVersionDesc(k.getId(), alice.getId()).orElseThrow();
        assertTrue(c.getLastResult().contains("secret GITHUB_TOKEN not set"), c.getLastResult());
        assertEquals(1, c.getConsecutiveFailures());
    }

    @Test
    void httpClientRedactsSecretsFromOutput() {
        var r = new SandboxClient.Request("u", "c", "python", "x", 5, List.of(), Map.of("A_KEY", "supersecret", "B_KEY", "supersecretlonger"));
        assertEquals("[REDACTED] and [REDACTED]", HttpSandboxClient.redact("supersecretlonger and supersecret", r));
    }
}
