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
        when(generator.propose(anyString(), anyString(), anyString()))
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

    private JsonNode waitJob(UserPrincipal u, String kpiName, String jobId) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode s = call(auth.session(u), "GET", "/api/kpis/" + kpiName + "/capability/jobs/" + jobId + "?after=0", null, 200);
            if (!"running".equals(s.get("state").asText())) return s;
            Thread.sleep(20);
        }
        throw new AssertionError("job did not finish");
    }

    // ── async jobs (UI path) ─────────────────────────────────────────────────

    @Test
    void asyncJobs_proposeThenApprove_streamStepsAndReturnParsedCapability() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString())).thenReturn(new CapabilityGenerator.Proposal(
                "Count the things", "SERVER", NO_ACCESS, "Widgets made per day", "Computed locally"));
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(5)");
        when(sandboxClient.run(any())).thenReturn(ok("5\n"));

        String jobId = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/jobs",
                "{\"action\":\"propose\",\"description\":\"count my widgets\"}", 202).get("jobId").asText();
        JsonNode done = waitJob(alice, "Widgets", jobId);
        assertEquals("done", done.get("state").asText());
        assertEquals("PROPOSED", done.at("/result/status").asText());
        assertEquals("Widgets made per day", done.at("/result/measures").asText());
        assertEquals("Drafting a plan on your runner", done.at("/events/0/text").asText());
        assertEquals("step", done.at("/events/0/kind").asText());
        // another user cannot see the job
        call(auth.session(bob), "GET", "/api/kpis/Widgets/capability/jobs/" + jobId + "?after=0", null, 404);

        String capId = done.at("/result/id").asText();
        String approveJob = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/jobs",
                "{\"action\":\"approve\",\"id\":\"" + capId + "\"}", 202).get("jobId").asText();
        JsonNode approved = waitJob(alice, "Widgets", approveJob);
        assertEquals("AWAITING_CONFIRM", approved.at("/result/status").asText());
        assertEquals(2, approved.get("events").size()); // writing + testing
        call(auth.session(alice), "GET", "/api/kpis/Widgets/capability/jobs/active", null, 204);
    }

    // ── S1 confirm gate / shadow ─────────────────────────────────────────────

    private String builtAwaitingConfirm(String stdout) throws Exception {
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(1)");
        when(sandboxClient.run(any())).thenReturn(ok(stdout));
        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "approve", 200).get("status").asText());
        return id;
    }

    @Test
    void jsonOutput_storesSampleAndEvidence_forTheConfirmScreen() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm("{\"value\": 47, \"unit\": \"min\", \"evidence\": [{\"label\": \"YouTube\", \"value\": \"40 min\"},"
                + " {\"label\": \"Docs\", \"value\": \"7 min\"}]}\n");
        JsonNode cap = call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200);
        assertEquals("47 min", cap.get("sampleText").asText());
        assertEquals(LocalDate.now().minusDays(1).toString(), cap.get("sampleDate").asText());
        assertEquals(2, cap.get("sampleEvidence").size());
        assertEquals("YouTube", cap.at("/sampleEvidence/0/label").asText());
        assertEquals("40 min", cap.at("/sampleEvidence/0/value").asText());
        assertEquals(id, cap.get("id").asText());
    }

    @Test
    void wrong_feedsReasonIntoNextGeneration_andComesBackForConfirmation() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm("{\"value\": 47, \"unit\": \"min\"}");
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(2)");
        when(sandboxClient.run(any())).thenReturn(ok("{\"value\": 120, \"unit\": \"min\"}"));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong",
                "{\"reason\":\"Shorts were not counted\"}", 200);

        assertEquals("AWAITING_CONFIRM", res.get("status").asText());
        assertEquals("120 min", res.get("sampleText").asText());
        assertEquals(1, res.get("confirmRounds").asInt());
        ArgumentCaptor<String> previousError = ArgumentCaptor.forClass(String.class);
        verify(generator).generate(any(), any(), any(), any(), any(), eq("print(1)"), previousError.capture());
        assertTrue(previousError.getValue().contains("Shorts were not counted"));
        assertTrue(previousError.getValue().contains("47 min"));
    }

    @Test
    void wrong_thirdTime_failsWithClearSentence_andNothingIsWired() throws Exception {
        KPI k = kpi(alice, "Widgets");
        String id = builtAwaitingConfirm("5");
        for (int round = 1; round < CapabilityAgentService.MAX_CONFIRM_ROUNDS; round++) {
            assertEquals("AWAITING_CONFIRM", call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong",
                    "{\"reason\":\"nope\"}", 200).get("status").asText());
        }
        JsonNode last = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong",
                "{\"reason\":\"still nope\"}", 200);
        assertEquals("TEST_FAILED", last.get("status").asText());
        assertTrue(last.get("failureReason").asText().startsWith("You said the number was wrong 3 times"));
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
    }

    @Test
    void wrong_needsAReason_andOnlyWhenAwaitingConfirm() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm("5");
        call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong", "{\"reason\":\" \"}", 400);
        act(alice, "Widgets", id, "confirm", 200);
        call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong", "{\"reason\":\"x\"}", 409);
        act(alice, "Widgets", id, "confirm", 409); // already SHADOW
    }

    @Autowired CapabilityLifecycleService lifecycle;

    @Test
    void shadow_becomesActive_afterShadowDays_andNotBefore() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm("5");
        act(alice, "Widgets", id, "confirm", 200);
        KPIProxyCapability shadow = capabilityRepository.findById(id).orElseThrow();
        assertEquals(CapabilityStatus.SHADOW, shadow.getStatus());
        assertEquals(LocalDate.now().plusDays(7), shadow.getShadowUntil());

        assertEquals(0, lifecycle.promoteDueShadows(LocalDate.now().plusDays(6)));
        assertEquals(CapabilityStatus.SHADOW, capabilityRepository.findById(id).orElseThrow().getStatus());
        assertEquals(1, lifecycle.promoteDueShadows(LocalDate.now().plusDays(7)));
        assertEquals(CapabilityStatus.ACTIVE, capabilityRepository.findById(id).orElseThrow().getStatus());
    }

    @Test
    void existingActiveCapability_isUntouchedByPromotion_andReadsBackWithoutNewFields() {
        KPI k = kpi(alice, "Widgets");
        KPIProxyCapability old = capabilityRepository.save(KPIProxyCapability.builder().userId(alice.getId()).kpiId(k.getId())
                .sourceCode("print(42)").status(CapabilityStatus.ACTIVE).version(1).build());
        assertEquals(0, lifecycle.promoteDueShadows(LocalDate.now().plusDays(365)));
        KPIProxyCapability after = capabilityRepository.findById(old.getId()).orElseThrow();
        assertEquals(CapabilityStatus.ACTIVE, after.getStatus());
        assertNull(after.getSampleValue());
        assertNull(after.getShadowUntil());
        assertNull(after.getRepair());
    }

    @Test
    void asyncJobs_noRunner_failsWithSentence() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString())).thenThrow(CapabilityGeneratorException.noRunner(null));
        String jobId = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/jobs",
                "{\"action\":\"propose\",\"description\":\"x\"}", 202).get("jobId").asText();
        JsonNode s = waitJob(alice, "Widgets", jobId);
        assertEquals("failed", s.get("state").asText());
        assertEquals("runner_offline", s.get("code").asText());
        assertEquals("Connect a runner to generate this capability", s.get("error").asText());
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void fullFlow_proposeApproveGenerateTestGate_becomesActiveAndWiresKpi() throws Exception {
        KPI k = kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), eq("Widgets"), anyString(), anyString(), any(), isNull(), isNull())).thenReturn("print(5)");
        when(sandboxClient.run(any())).thenReturn(ok("noise\n5\n"));

        JsonNode proposed = propose(alice, "Widgets", 200);
        assertEquals("PROPOSED", proposed.get("status").asText());
        assertEquals("Count the things", proposed.get("proposal").asText());
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sandboxClient);
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType(), "not wired before approval");

        JsonNode approved = act(alice, "Widgets", proposed.get("id").asText(), "approve", 200);
        assertEquals("AWAITING_CONFIRM", approved.get("status").asText());
        assertEquals(5.0, approved.get("sampleValue").asDouble());
        assertEquals("print(5)", approved.get("sourceCode").asText());
        assertEquals(1, approved.get("attempts").asInt());

        ArgumentCaptor<SandboxClient.Request> req = ArgumentCaptor.forClass(SandboxClient.Request.class);
        verify(sandboxClient).run(req.capture());
        assertEquals(alice.getId(), req.getValue().userId());
        assertTrue(req.getValue().sourceCode().startsWith("TARGET_DATE = \"" + LocalDate.now().minusDays(1) + "\""));
        assertTrue(req.getValue().sourceCode().endsWith("print(5)"));

        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType(), "not wired before the user confirms");
        assertEquals("SHADOW", act(alice, "Widgets", proposed.get("id").asText(), "confirm", 200).get("status").asText());
        KPI after = kpiRepository.findById(k.getId()).orElseThrow();
        assertEquals(ProxyType.SANDBOX_CAPABILITY, after.getProxyType());
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals("SHADOW", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());
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
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sandboxClient);
    }

    @Test
    void nonServerPlatformProposal_isRejected() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString()))
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
        when(generator.generate(any(), any(), any(), any(), any(), any(), any()))
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
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("raise SystemExit(1)");
        when(sandboxClient.run(any())).thenReturn(nonZero("Traceback: boom"));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode first = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", first.get("status").asText());
        assertTrue(first.get("failureReason").asText().contains("boom"));
        assertEquals("TEST_FAILED", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());

        JsonNode second = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(2, second.get("attempts").asInt());
        verify(generator).generate(any(), eq("Widgets"), anyString(), anyString(), any(),
                eq("raise SystemExit(1)"), org.mockito.ArgumentMatchers.contains("boom"));

        JsonNode third = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(3, third.get("attempts").asInt());
        act(alice, "Widgets", id, "regenerate", 409); // cap reached
        verify(generator, times(3)).generate(any(), any(), any(), any(), any(), any(), any());
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
    }

    @Test
    void regenerateThatPasses_goesActive() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print('x')", "print(2)");
        when(sandboxClient.run(any())).thenReturn(ok("not a number"), ok("2"));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode first = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", first.get("status").asText());
        assertTrue(first.get("failureReason").asText().contains("not a number"));
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "regenerate", 200).get("status").asText());
    }

    @Test
    void generatorOutage_isFailureWithReason_notCountedAsAttempt() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any()))
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
        when(generator.propose(anyString(), anyString(), anyString())).thenThrow(new CapabilityGeneratorException("busy", true, null));
        propose(alice, "Widgets", 409);
    }

    @Test
    void proposeWithNoRunnerOnline_is503WithConnectRunnerMessage() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString())).thenThrow(CapabilityGeneratorException.noRunner(null));
        JsonNode res = propose(alice, "Widgets", 503);
        assertEquals(CapabilityGeneratorException.NO_RUNNER_MESSAGE, res.get("error").asText());
    }

    @Test
    void approveWithNoRunnerOnline_failsWithoutCountingAnAttempt_thenRegenerates() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(CapabilityGeneratorException.noRunner(null)).thenReturn("print(4)");
        when(sandboxClient.run(any())).thenReturn(ok("4\n"));
        String id = propose(alice, "Widgets", 200).get("id").asText();

        JsonNode failed = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", failed.get("status").asText());
        assertEquals("Code generation failed: " + CapabilityGeneratorException.NO_RUNNER_MESSAGE,
                failed.get("failureReason").asText());
        assertEquals(0, capabilityRepository.findById(id).orElseThrow().getAttempts() == null ? 0
                : capabilityRepository.findById(id).orElseThrow().getAttempts());

        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "regenerate", 200).get("status").asText());
    }

    @Test
    void rejectedProposal_cannotBeApproved() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(NO_ACCESS);
        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("REJECTED", act(alice, "Widgets", id, "reject", 200).get("status").asText());
        act(alice, "Widgets", id, "approve", 409);
        verify(generator, never()).generate(any(), any(), any(), any(), any(), any(), any());
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
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(9)");
        when(sandboxClient.run(any())).thenReturn(ok("9"));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/repair", null, 200);

        assertEquals("ACTIVE", res.get("status").asText());
        assertEquals(2, res.get("version").asInt());
        verify(generator).generate(any(), eq("Widgets"), eq("count widgets"), eq("spec"), any(),
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
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(nope)");
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
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("a", "b", "c");
        when(sandboxClient.run(any())).thenReturn(nonZero("x"), nonZero("y"), ok("1"));

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.ACTIVE, res.getStatus());
        assertEquals(3, res.getAttempts());
        assertEquals(ProxyStatus.ACTIVE, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    @Test
    void autoRepair_givesUpAtCap_leavingKpiNeedingRepair() {
        KPI k = brokenActiveKpi(alice);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("a");
        when(sandboxClient.run(any())).thenReturn(nonZero("x"));

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.TEST_FAILED, res.getStatus());
        verify(generator, times(3)).generate(any(), any(), any(), any(), any(), any(), any());
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

        verify(generator, never()).generate(any(), any(), any(), any(), any(), any(), any());
        KPIProxyCapability stored = capabilityRepository.findById(id).orElseThrow();
        assertEquals(CapabilityStatus.PROPOSED, stored.getStatus());
        assertEquals(alice.getId(), stored.getUserId());
        assertEquals(ProxyType.NONE, kpiRepository.findById(bobKpi.getId()).orElseThrow().getProxyType());
        assertEquals(ProxyType.NONE, kpiRepository.findById(aliceKpi.getId()).orElseThrow().getProxyType());

        // Alice still can.
        generatorProposes(NO_ACCESS);
        when(generator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn("print(1)");
        when(sandboxClient.run(any())).thenReturn(ok("1"));
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "approve", 200).get("status").asText());
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        int s = mockMvc.perform(get("/api/kpis/Widgets/capability")).andReturn().getResponse().getStatus();
        assertTrue(s == 302 || s == 401 || s == 403, "unexpected status " + s); // redirect to login or denied
    }
}
