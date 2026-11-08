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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 agent loop over the real controller + CapabilityAgentService + CapabilityLifecycleService + Mongo:
 * intake -> proposal (runner-only, no platform choice) -> approve -> build/test gate -> confirm -> SHADOW -> ACTIVE.
 * The LLM (CapabilityGenerator) and the runner build (RunnerCollectorPipeline.attempt) are mocked; the pipeline
 * itself is covered end to end by RunnerCollectorIntegrationTest. Also covers source rejection, the attempt cap,
 * M12 repair and cross-user isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CapabilityAgentIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean CapabilityGenerator generator;
    @MockitoBean RunnerCollectorPipeline pipeline;

    @Autowired MockMvc mockMvc;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired JwtUtil jwtUtil;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilityRepository;
    @Autowired CapabilityAgentService service;
    @Autowired CapabilityLifecycleService lifecycle;
    final ObjectMapper mapper = new ObjectMapper();

    AuthTestHelper auth;
    UserPrincipal alice;
    UserPrincipal bob;
    /** failureReason of the capability at the moment each build attempt started (the prompt feedback). */
    final List<String> attemptFeedback = new ArrayList<>();

    static final List<String> AW = List.of("activitywatch");

    @BeforeEach
    void setUp() {
        auth = new AuthTestHelper(mongoTemplate, jwtUtil);
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        mongoTemplate.dropCollection(User.class);
        reset(generator, pipeline);
        attemptFeedback.clear();
        alice = auth.register("alice@example.com");
        bob = auth.register("bob@example.com");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private KPI kpi(UserPrincipal u, String name) {
        return kpiRepository.save(KPI.builder().name(name).userId(u.getId()).active(true).higherIsBetter(true).build());
    }

    private void generatorProposes(List<String> sources) {
        when(generator.propose(anyString(), anyString(), anyString(), any()))
                .thenReturn(new CapabilityGenerator.Proposal("Count the things", sources));
    }

    /** One runner build+test outcome per call: a CapabilityOutput passes the gate, a String fails it with that reason. */
    private void builds(Object... outcomes) {
        List<Object> queue = new ArrayList<>(List.of(outcomes));
        doAnswer(inv -> {
            KPIProxyCapability cap = inv.getArgument(0);
            KPI k = inv.getArgument(1);
            attemptFeedback.add(cap.getFailureReason());
            Object o = queue.size() > 1 ? queue.remove(0) : queue.get(0);
            cap.setAttempts((cap.getAttempts() == null ? 0 : cap.getAttempts()) + 1);
            if (o instanceof CapabilityOutput out) {
                return lifecycle.testGatePassed(cap, k, out, LocalDate.now().minusDays(1), Boolean.TRUE.equals(cap.getRepair()));
            }
            cap.setStatus(CapabilityStatus.TEST_FAILED);
            cap.setFailureReason("Fixture test failed: " + o);
            return capabilityRepository.save(cap);
        }).when(pipeline).attempt(any(), any(), anyInt());
    }

    private static CapabilityOutput value(double v) {
        return new CapabilityOutput(v, null, List.of());
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
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "Count the things", "Widgets made per day", "Computed locally", AW));
        builds(value(5));

        String jobId = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/jobs",
                "{\"action\":\"propose\",\"description\":\"count my widgets\"}", 202).get("jobId").asText();
        JsonNode done = waitJob(alice, "Widgets", jobId);
        assertEquals("done", done.get("state").asText());
        assertEquals("PROPOSED", done.at("/result/status").asText());
        assertEquals("RUNNER", done.at("/result/platform").asText());
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
        call(auth.session(alice), "GET", "/api/kpis/Widgets/capability/jobs/active", null, 204);
    }

    // ── S1 confirm gate / shadow ─────────────────────────────────────────────

    private String builtAwaitingConfirm(CapabilityOutput out) throws Exception {
        generatorProposes(AW);
        builds(out);
        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "approve", 200).get("status").asText());
        return id;
    }

    @Test
    void sampleAndEvidence_areStoredForTheConfirmScreen() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm(new CapabilityOutput(47, "min", List.of(
                new CapabilityOutput.Row("YouTube", "40 min"), new CapabilityOutput.Row("Docs", "7 min"))));
        JsonNode cap = call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200);
        assertEquals("47 min", cap.get("sampleText").asText());
        assertEquals(LocalDate.now().minusDays(1).toString(), cap.get("sampleDate").asText());
        assertEquals(2, cap.get("sampleEvidence").size());
        assertEquals("YouTube", cap.at("/sampleEvidence/0/label").asText());
        assertEquals("40 min", cap.at("/sampleEvidence/0/value").asText());
        assertEquals(id, cap.get("id").asText());
    }

    @Test
    void wrong_feedsReasonIntoNextBuild_andComesBackForConfirmation() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm(new CapabilityOutput(47, "min", List.of()));
        builds(new CapabilityOutput(120, "min", List.of()));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong",
                "{\"reason\":\"Shorts were not counted\"}", 200);

        assertEquals("AWAITING_CONFIRM", res.get("status").asText());
        assertEquals("120 min", res.get("sampleText").asText());
        assertEquals(1, res.get("confirmRounds").asInt());
        String feedback = attemptFeedback.get(attemptFeedback.size() - 1);
        assertTrue(feedback.contains("Shorts were not counted"), feedback);
        assertTrue(feedback.contains("47 min"), feedback);
    }

    @Test
    void wrong_thirdTime_failsWithClearSentence_andNothingIsWired() throws Exception {
        KPI k = kpi(alice, "Widgets");
        String id = builtAwaitingConfirm(value(5));
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
        String id = builtAwaitingConfirm(value(5));
        call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong", "{\"reason\":\" \"}", 400);
        act(alice, "Widgets", id, "confirm", 200);
        call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/" + id + "/wrong", "{\"reason\":\"x\"}", 409);
        act(alice, "Widgets", id, "confirm", 409); // already SHADOW
    }

    @Test
    void shadow_becomesActive_afterShadowDays_andNotBefore() throws Exception {
        kpi(alice, "Widgets");
        String id = builtAwaitingConfirm(value(5));
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
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenThrow(CapabilityGeneratorException.noRunner(null));
        String jobId = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/jobs",
                "{\"action\":\"propose\",\"description\":\"x\"}", 202).get("jobId").asText();
        JsonNode s = waitJob(alice, "Widgets", jobId);
        assertEquals("failed", s.get("state").asText());
        assertEquals("runner_offline", s.get("code").asText());
        assertEquals("Connect a runner to generate this capability", s.get("error").asText());
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void fullFlow_proposeApproveBuild_confirm_wiresKpiToTheRunnerCollector() throws Exception {
        KPI k = kpi(alice, "Widgets");
        generatorProposes(AW);
        builds(value(5));

        JsonNode proposed = propose(alice, "Widgets", 200);
        assertEquals("PROPOSED", proposed.get("status").asText());
        assertEquals("RUNNER", proposed.get("platform").asText());
        assertEquals("Count the things", proposed.get("proposal").asText());
        assertEquals("activitywatch", proposed.at("/declaredCapabilities/sources/0").asText());
        verify(pipeline, never()).attempt(any(), any(), anyInt()); // only the credential-list lookup happened
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType(), "not wired before approval");

        JsonNode approved = act(alice, "Widgets", proposed.get("id").asText(), "approve", 200);
        assertEquals("AWAITING_CONFIRM", approved.get("status").asText());
        assertEquals(5.0, approved.get("sampleValue").asDouble());
        assertEquals(1, approved.get("attempts").asInt());

        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType(), "not wired before the user confirms");
        assertEquals("SHADOW", act(alice, "Widgets", proposed.get("id").asText(), "confirm", 200).get("status").asText());
        KPI after = kpiRepository.findById(k.getId()).orElseThrow();
        assertEquals(ProxyType.RUNNER_COLLECTOR, after.getProxyType());
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals("SHADOW", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());
    }

    // ── enforcement: only what a runner collector can read ───────────────────

    @Test
    void proposalWithNoLocalSource_isRejectedBeforeAnythingIsStoredOrBuilt() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(List.of());
        JsonNode err = propose(alice, "Widgets", 422);
        assertTrue(err.get("error").asText().contains("can't be captured yet"), err.toString());

        for (List<String> bad : List.of(List.of("http"), List.of("activitywatch", "network"))) {
            generatorProposes(bad);
            propose(alice, "Widgets", 422);
        }
        assertEquals(0, capabilityRepository.count());
        verify(pipeline, never()).attempt(any(), any(), anyInt()); // only the credential-list lookup happened
    }

    @Test
    void retiredServerCapability_cannotBeApprovedOrRegenerated_andANewProposalStartsOver() throws Exception {
        KPI k = kpi(alice, "Widgets");
        KPIProxyCapability retired = capabilityRepository.save(KPIProxyCapability.builder().userId(alice.getId())
                .kpiId(k.getId()).platform(null).status(CapabilityStatus.RETIRED).version(1).build());
        act(alice, "Widgets", retired.getId(), "approve", 409);
        act(alice, "Widgets", retired.getId(), "regenerate", 409);
        assertEquals("RETIRED", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());

        generatorProposes(AW);
        JsonNode fresh = propose(alice, "Widgets", 200);
        assertEquals(2, fresh.get("version").asInt());
        assertEquals("RUNNER", fresh.get("platform").asText());
        verify(pipeline, never()).attempt(any(), any(), anyInt()); // only the credential-list lookup happened
    }

    // ── test gate failure + retry cap ────────────────────────────────────────

    @Test
    void failedGate_storesVisibleReason_regenerateFeedsErrorBack_andCapsAttempts() throws Exception {
        KPI k = kpi(alice, "Widgets");
        generatorProposes(AW);
        builds("busy-day expected 47 got 12");

        String id = propose(alice, "Widgets", 200).get("id").asText();
        JsonNode first = act(alice, "Widgets", id, "approve", 200);
        assertEquals("TEST_FAILED", first.get("status").asText());
        assertTrue(first.get("failureReason").asText().contains("got 12"));
        assertEquals("TEST_FAILED", call(auth.session(alice), "GET", "/api/kpis/Widgets/capability", null, 200).get("status").asText());

        JsonNode second = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(2, second.get("attempts").asInt());
        assertTrue(attemptFeedback.get(1).contains("got 12"), "the failure is fed into the next build");

        JsonNode third = act(alice, "Widgets", id, "regenerate", 200);
        assertEquals(3, third.get("attempts").asInt());
        act(alice, "Widgets", id, "regenerate", 409); // cap reached
        verify(pipeline, times(3)).attempt(any(), any(), anyInt());
        assertEquals(ProxyType.NONE, kpiRepository.findById(k.getId()).orElseThrow().getProxyType());
    }

    @Test
    void regenerateThatPasses_comesBackForConfirmation() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(AW);
        builds("not a number", value(2));

        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("TEST_FAILED", act(alice, "Widgets", id, "approve", 200).get("status").asText());
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "regenerate", 200).get("status").asText());
    }

    @Test
    void proposeWhenGeneratorBusy_is409() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenThrow(new CapabilityGeneratorException("busy", true, null));
        propose(alice, "Widgets", 409);
    }

    @Test
    void proposeWithNoRunnerOnline_is503WithConnectRunnerMessage() throws Exception {
        kpi(alice, "Widgets");
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenThrow(CapabilityGeneratorException.noRunner(null));
        JsonNode res = propose(alice, "Widgets", 503);
        assertEquals(CapabilityGeneratorException.NO_RUNNER_MESSAGE, res.get("error").asText());
    }

    @Test
    void rejectedProposal_cannotBeApproved() throws Exception {
        kpi(alice, "Widgets");
        generatorProposes(AW);
        String id = propose(alice, "Widgets", 200).get("id").asText();
        assertEquals("REJECTED", act(alice, "Widgets", id, "reject", 200).get("status").asText());
        act(alice, "Widgets", id, "approve", 409);
        verify(pipeline, never()).attempt(any(), any(), anyInt());
    }

    // ── M12 repair ───────────────────────────────────────────────────────────

    private KPI brokenActiveKpi(UserPrincipal u) {
        KPI k = kpiRepository.save(KPI.builder().name("Widgets").userId(u.getId()).active(true)
                .proxyType(ProxyType.RUNNER_COLLECTOR).proxyStatus(ProxyStatus.NEEDS_REPAIR)
                .consecutiveProxyFailures(3).build());
        capabilityRepository.save(KPIProxyCapability.builder().userId(u.getId()).kpiId(k.getId())
                .platform(CapabilityPlatform.RUNNER).description("count widgets").proposal("spec")
                .declaredCapabilities(Map.of("sources", AW)).collectorId("kpi-" + k.getId()).runnerDeviceId("dev-1")
                .status(CapabilityStatus.ACTIVE).version(1)
                .lastResult("FAILED on runner: NameError old").build());
        return k;
    }

    @Test
    void manualRepair_createsNewVersionSeededWithFailure_clearsNeedsRepairOnPass() throws Exception {
        KPI k = brokenActiveKpi(alice);
        builds(value(9));

        JsonNode res = call(auth.session(alice), "POST", "/api/kpis/Widgets/capability/repair", null, 200);

        assertEquals("ACTIVE", res.get("status").asText());
        assertEquals(2, res.get("version").asInt());
        assertTrue(attemptFeedback.get(0).contains("NameError old"), attemptFeedback.toString());
        KPI after = kpiRepository.findById(k.getId()).orElseThrow();
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals(0, after.getConsecutiveProxyFailures());
        List<KPIProxyCapability> caps = capabilityRepository.findByKpiIdAndUserId(k.getId(), alice.getId());
        assertEquals(1, caps.stream().filter(c -> c.getStatus() == CapabilityStatus.ACTIVE).count());
        assertTrue(caps.stream().anyMatch(c -> c.getVersion() == 1 && c.getStatus() == CapabilityStatus.DISABLED));
        KPIProxyCapability v2 = caps.stream().filter(c -> c.getVersion() == 2).findFirst().orElseThrow();
        assertEquals("kpi-" + k.getId(), v2.getCollectorId(), "a repair re-installs under the same collector id");
    }

    @Test
    void repairThatFailsTestGate_keepsKpiNeedingRepair() throws Exception {
        KPI k = brokenActiveKpi(alice);
        builds("still broken");

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
        verifyNoInteractions(generator, pipeline);
    }

    @Test
    void autoRepair_retriesUpToCap_untilTestGatePasses() {
        KPI k = brokenActiveKpi(alice);
        builds("x", "y", value(1));

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.ACTIVE, res.getStatus());
        assertEquals(3, res.getAttempts());
        assertEquals(ProxyStatus.ACTIVE, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    @Test
    void autoRepair_givesUpAtCap_leavingKpiNeedingRepair() {
        KPI k = brokenActiveKpi(alice);
        builds("x");

        KPIProxyCapability res = service.repairFor(alice.getId(), k, true);

        assertEquals(CapabilityStatus.TEST_FAILED, res.getStatus());
        verify(pipeline, times(3)).attempt(any(), any(), anyInt());
        assertEquals(ProxyStatus.NEEDS_REPAIR, kpiRepository.findById(k.getId()).orElseThrow().getProxyStatus());
    }

    // ── cross-user isolation (cf. b8a1057) ───────────────────────────────────

    @Test
    void anotherUser_cannotSeeOrDriveMyCapability() throws Exception {
        KPI aliceKpi = kpi(alice, "Widgets");
        generatorProposes(AW);
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

        verify(pipeline, never()).attempt(any(), any(), anyInt()); // only the credential-list lookup happened
        KPIProxyCapability stored = capabilityRepository.findById(id).orElseThrow();
        assertEquals(CapabilityStatus.PROPOSED, stored.getStatus());
        assertEquals(alice.getId(), stored.getUserId());
        assertEquals(ProxyType.NONE, kpiRepository.findById(bobKpi.getId()).orElseThrow().getProxyType());
        assertEquals(ProxyType.NONE, kpiRepository.findById(aliceKpi.getId()).orElseThrow().getProxyType());

        // Alice still can.
        builds(value(1));
        assertEquals("AWAITING_CONFIRM", act(alice, "Widgets", id, "approve", 200).get("status").asText());
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        int s = mockMvc.perform(get("/api/kpis/Widgets/capability")).andReturn().getResponse().getStatus();
        assertTrue(s == 302 || s == 401 || s == 403, "unexpected status " + s); // redirect to login or denied
    }
}
