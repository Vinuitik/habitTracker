package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.AuthTestHelper;
import habitTracker.auth.JwtUtil;
import habitTracker.auth.User;
import habitTracker.auth.UserPrincipal;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8: intake -> proposal -> approve -> generate -> sandbox test gate -> ACTIVE, over the real
 * controller + service + Mongo. The LLM (CapabilityGenerator) and the sandbox sidecar
 * (SandboxClient) are mocked; no CLI or docker is involved. Also covers failure/retry cap,
 * ungrantable-host rejection, M12 repair, and cross-user isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CapabilityAgentIntegrationTest {

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
    @Autowired CapabilityAgentService service;
    final ObjectMapper mapper = new ObjectMapper();

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;

    static final Map<String, List<String>> NO_ACCESS = Map.of("network", List.of(), "secrets", List.of());

    @BeforeEach
    void setUp() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        mongoTemplate.dropCollection(User.class);
        reset(generator, sandboxClient);
        alice = auth.register("alice@example.com");
        bob = auth.register("bob@example.com");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private KPI kpi(UserPrincipal u, String name) {
        return kpiRepository.save(KPI.builder().name(name).userId(u.getId()).active(true).higherIsBetter(true).build());
    }

    private static SandboxClient.Result ok(String stdout) {
        return new SandboxClient.Result(SandboxClient.Outcome.OK, 0, stdout, "");
    }

    private static SandboxClient.Result nonZero(String stderr) {
        return new SandboxClient.Result(SandboxClient.Outcome.NONZERO_EXIT, 1, "", stderr);
    }

    private void generatorProposes(Map<String, List<String>> declared) {
        when(generator.propose(anyString(), anyString()))
                .thenReturn(new CapabilityGenerator.Proposal("Count the things", "SERVER", declared));
    }

    private JsonNode call(RequestPostProcessor user, String method, String path, String body, int expected) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path);
        req = req.with(user).with(csrf());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String json = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.isEmpty() ? null : mapper.readTree(json);
    }

    private JsonNode propose(UserPrincipal u, String kpiName, int expected) throws Exception {
        return call(auth.session(u), "POST", "/api/kpis/" + kpiName + "/capability/propose",
                "{\"description\":\"count my widgets\"}", expected);
    }

    private JsonNode act(UserPrincipal u, String kpiName, String id, String action, int expected) throws Exception {
        return call(auth.session(u), "POST", "/api/kpis/" + kpiName + "/capability/" + id + "/" + action, null, expected);
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void fullFlow_proposeApproveGenerateTestGate_becomesActiveAndWiresKpi() throws Exception {
        KPI k = kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(eq("Widgets"), anyString(), anyString(), any(), isNull(), isNull())).thenReturn("print(5)");
        when(sandboxClient.run(any())).thenReturn(ok("noise\n5\n"));

        JsonNode proposed = propose(alice, "Widgets", 200);
        assertEquals("PROPOSED", proposed.get("status").asText());
        assertEquals("Count the things", proposed.get("proposal").asText());
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sandboxClient);
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType(), "not wired before approval");

        JsonNode approved = act(alice, "Widgets", proposed.get("id").asText(), "approve", 200);
        assertEquals("ACTIVE", approved.get("status").asText());
        assertEquals("print(5)", approved.get("sourceCode").asText());
        assertEquals(1, approved.get("attempts").asInt());

        ArgumentCaptor<SandboxClient.Request> req = ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(req.capture());
        assertEquals(alice.getId(), req.getValue().userId());
        assertTrue(req.getValue().sourceCode().startsWith("TARGET_DATE = \"" + LocalDate.now().minusDays(1) + "\""));
        assertTrue(req.getValue().sourceCode().endsWith("print(5)"));

        KPI after = kpiRepository.findById(k.getId()).orElseThrow();
        assertEquals(ProxyType.SANDBOX_CAPABILITY, after.getProxyType());
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals("ACTIVE", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());
    }

    // ── enforcement: what the sandbox cannot grant ───────────────────────────

    @Test
    void proposalWithUngrantableHostsOrSecretNames_isRejectedBeforeAnythingIsStoredOrGenerated() throws Exception {
        kpi(alice, "Widgets");
        for (String host : List.of("127.0.0.1", "localhost", "javaapp", "mongodbHabit", "egress-proxy", "x.internal",
                "*.example.com", "api.example.com:8080", "10.0.0.5", "foo.local")) {
            generatorProposes(Map.of("network", List.of(host), "secrets", List.of()));
            JsonNode err = propose(alice, "Widgets", 422);
            assertTrue(err.get("error").asText().contains("cannot be granted"), host + " -> " + err);
        }
        for (String secret : List.of("HTTPS_PROXY", "PATH", "bad name", "1ABC", "PYTHONPATH")) {
            generatorProposes(Map.of("network", List.of(), "secrets", List.of(secret)));
            propose(alice, "Widgets", 422);
        }

        assertEquals(0, capabilityRepository.count());
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void nonServerPlatformProposal_isRejected() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString()))
                .thenReturn(new CapabilityGenerator.Proposal("Read steps", "ANDROID", NO_ACCESS));
        propose(alice, "Widgets", 422);
        assertEquals(0, capabilityRepository.count());
    }

    @Test
    void scriptNeedingNetwork_failsTheTestGate_inTheSandboxPath() throws Exception {
        // The proposal lied (declared nothing) but the script reaches for the network. The sandbox is
        // --network none (verified for real in sandbox/real_run_check.py), so the dry run fails
        // and the capability never goes live.
        KPI k = kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn("import urllib.request\nprint(urllib.request.urlopen('https://api.example.com').read())");
        when(sandboxClient.run(any())).thenReturn(nonZero("URLError: <urlopen error [Errno -3] Temporary failure in name resolution>"));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode res = act(alice, "Widgets", id, "approve", 200);

        assertEquals("TEST_FAILED", res.get("status").asText());
        assertTrue(res.get("failureReason").asText().contains("name resolution"), res.toString());
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
    }

    // ── test gate failure + retry cap ────────────────────────────────────────

    @Test
    void failedGate_storesVisibleReason_regenerateFeedsErrorBack_andCapsAttempts() throws Exception {
        KPI k = kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("raise SystemExit(1)");
        when(sandboxClient.run(any())).thenReturn(nonZero("Traceback: boom"));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode first = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", first.get("status").asText());
        assertTrue(first.get("failureReason").asText().contains("boom"));
        assertEquals("TEST_FAILED", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());

        JsonNode second = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(2, second.get("attempts").asInt());
        verify(generator).generate(eq("Widgets"), anyString(), anyString(), any(),
                eq("raise SystemExit(1)"), org.mockito.ArgumentMatchers.contains("boom"));

        JsonNode third = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(3, third.get("attempts").asInt());
        act(alice, "Widgets", id, "regenerate", 409); // cap reached
        verify(generator, times(3)).generate(any(), any(), any(), any(), any(), any());
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
    }

    @Test
    void regenerateThatPasses_goesActive() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print('x')", "print(2)");
        when(sandboxClient.run(any())).thenReturn(ok("not a number"), ok("2"));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode first = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", first.get("status").asText());
        assertTrue(first.get("failureReason").asText().contains("not a number"));
        assertEquals("ACTIVE", act(alice, "Widgets", id, "regenerate", 200).get("status").asText());
    }

    @Test
    void generatorOutage_isFailureWithReason_notCountedAsAttempt() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new CapabilityGeneratorException("Generator upstream 502: auth expired", false, null));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode res = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", res.get("status").asText());
        assertEquals(0, res.get("attempts").asInt());
        assertTrue(res.get("failureReason").asText().contains("auth expired"));
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void proposeWhenGeneratorBusy_is409() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString())).thenThrow(new CapabilityGeneratorException("busy", true, null));
        propose(alice, "Widgets", 409);
    }

    @Test
    void rejectedProposal_cannotBeApproved() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("REJECTED", act(alice, "Widgets", id, "reject", 200).get("status").asText());
        act(alice, "Widgets", id, "approve", 409);
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
    }

    // ── M12 repair ───────────────────────────────────────────────────────────

    private KPI brokenActiveKpi(UserPrincipal u) {
        KPI k = kpiRepository.save(KPI.builder().name("Widgets").userId(u.getId()).active(true)
                .proxyType(ProxyType.SANDBOX_CAPABILITY).proxyStatus(ProxyStatus.NEEDS_REPAIR)
                .consecutiveProxyFailures(3).build());
        capabilityRepository.save(KPIProxyCapability.builder().userId(u.getId()).kpiId(k.getId())
                .description("count widgets").proposal("spec").declaredCapabilities(NO_ACCESS)
                .sourceCode("print(old)").status(CapabilityStatus.ACTIVE).version(1)
                .lastResult("NONZERO_EXIT exit=1: NameError old").build());
        return k;
    }

    @Test
    void manualRepair_createsNewVersionSeededWithFailure_clearsNeedsRepairOnPass() throws Exception {
        KPI k = brokenActiveKpi(alice);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print(9)");
        when(sandboxClient.run(any())).thenReturn(ok("9"));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/repair", null, 200);

        assertEquals("ACTIVE", res.get("status").asText());
        assertEquals(2, res.get("version").asInt());
        verify(generator).generate(eq("Widgets"), eq("count widgets"), eq("spec"), any(),
                eq("print(old)"), org.mockito.ArgumentMatchers.contains("NameError old"));
        KPI after = kpiRepository.findById(k.getId()).orElseThrow();
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals(0, after.getConsecutiveProxyFailures());
        List<KPIProxyCapability> caps = capabilityRepository.findByKpiIdAndUserId(k.getId(), alice.getId());
        assertEquals(1, caps.stream().filter(c -> c.getStatus() == CapabilityStatus.ACTIVE).count());
        assertTrue(caps.stream().anyMatch(c -> c.getVersion() == 1 && c.getStatus() == CapabilityStatus.DISABLED));
    }

    @Test
    void repairThatFailsTestGate_keepsKpiNeedingRepair() throws Exception {
        KPI k = brokenActiveKpi(alice);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print(nope)");
        when(sandboxClient.run(any())).thenReturn(nonZero("still broken"));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/repair", null, 200);

        assertEquals("TEST_FAILED", res.get("status").asText());
        assertEquals(ProxyStatus.NEEDS_REPAIR, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    @Test
    void repairNotAllowed_whenKpiHealthy() throws Exception {
        KPI k = brokenActiveKpi(alice);
        k.setProxyStatus(ProxyStatus.ACTIVE);
        kpiRepository.save(k);
        call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/repair", null, 409);
        verifyNoInteractions(generator);
    }

    @Test
    void autoRepair_retriesUpToCap_untilTestGatePasses() {
        KPI k = brokenActiveKpi(alice);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("a", "b", "c");
        when(sandboxClient.run(any())).thenReturn(nonZero("x"), nonZero("y"), ok("1"));

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.ACTIVE, res.getStatus());
        assertEquals(3, res.getAttempts());
        assertEquals(ProxyStatus.ACTIVE, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    @Test
    void autoRepair_givesUpAtCap_leavingKpiNeedingRepair() {
        KPI k = brokenActiveKpi(alice);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("a");
        when(sandboxClient.run(any())).thenReturn(nonZero("x"));

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.TEST_FAILED, res.getStatus());
        verify(generator, times(3)).generate(any(), any(), any(), any(), any(), any());
        assertEquals(ProxyStatus.NEEDS_REPAIR, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    // ── cross-user isolation (cf. b8a1057) ───────────────────────────────────

    @Test
    void anotherUser_cannotSeeOrDriveMyCapability() throws Exception {
        KPI aliceKpi = kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        String id = propose(alice, "Widgets", 200).get("id").asText();

        // Bob has no KPI of that name: every endpoint is a 400, nothing leaks.
        call(auth.session(bob), "GET", "/api/kpis/Widgets/capability", null, 400);
        act(bob, "Widgets", id, "approve", 400);
        act(bob, "Widgets", id, "reject", 400);
        act(bob, "Widgets", id, "regenerate", 400);
        call(auth.session(bob), "POST", "/api/kpis/Widgets/capability/repair", null, 400);

        // Bob has his own same-named KPI and guesses Alice's capability id: still not found.
        KPI bobKpi = kpi(bob, "Widgets");
        call(auth.session(bob), "GET", "/api/kpis/Widgets/capability", null, 204);
        act(bob, "Widgets", id, "approve", 400);
        act(bob, "Widgets", id, "reject", 400);

        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
        KPIProxyCapability stored = capabilityRepository.findById(id).orElseThrow();
        assertEquals(CapabilityStatus.PROPOSED, stored.getStatus());
        assertEquals(alice.getId(), stored.getUserId());
        assertEquals(ProxyType.NONE, kpiRepository.findById(bobKpi.getId()).orElseThrow().getProxyType());
        assertEquals(ProxyType.NONE, kpiRepository.findById(aliceKpi.getId()).orElseThrow().getProxyType());

        // Alice still can.
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn("print(1)");
        when(sandboxClient.run(any())).thenReturn(ok("1"));
        assertEquals("ACTIVE", act(alice, "Widgets", id, "approve", 200).get("status").asText());
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        int s = mockMvc.perform(get("/api/kpis/Widgets/capability")).andReturn().getResponse().getStatus();
        assertTrue(s == 302 || s == 401 || s == 403, "unexpected status " + s); // redirect to login or denied
    }
}
