package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import habitTracker.relay.OnlineRunnerSelector;
import habitTracker.relay.RunnerClient;
import habitTracker.relay.RunnerUnavailableException;
import habitTracker.relay.UplinkRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * S3 RUNNER platform end to end over the real services + Mongo, with a fake runner behind RunnerClient (the uplink
 * transport) and a mocked LLM (CapabilityGenerator). Covers: happy path propose → contract → dry run → confirm →
 * SHADOW + activate → readings pull; repair with an unchanged vs changed contract; sandbox unavailable;
 * ActivityWatch missing + install; fixture failure → repair loop; runner offline → pull skipped, breaker untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"kpi.runner.sensor-poll-ms=5", "kpi.runner.pull-cron=-", "kpi.shadow.promote-cron=-"})
@Testcontainers
class RunnerCollectorIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean CapabilityGenerator generator;
    @MockitoBean RunnerClient runnerClient;
    @MockitoBean OnlineRunnerSelector selector;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilityRepository;
    @Autowired habitTracker.KPI.deploy.KpiDeploymentRepository deploymentRepository;
    @Autowired CapabilityAgentService service;
    @Autowired CapabilityLifecycleService lifecycle;
    @Autowired RunnerCollectorProvider provider;

    static final ObjectMapper JSON = new ObjectMapper();
    static final String USER = "user-1";
    static final String DEVICE = "dev-1";

    FakeRunner runner;

    /** In-memory runner implementing the kpi-sensors.md "Runner API" table. */
    static final class FakeRunner {
        boolean online = true;
        boolean sandboxAvailable = true;
        boolean awInstalled = true;
        boolean awRunning = true;
        int installPolls = 0;
        List<Boolean> testOutcomes = new ArrayList<>(); // consumed per /test call; empty → pass
        String currentHash;
        String approvedHash;
        boolean active;
        String state = "pending";
        int installs, approves, runs, activates, sensorInstalls;
        List<Map<String, Object>> readings = new ArrayList<>();
        List<Map<String, Object>> credentials = new ArrayList<>(); // GET /v1/credentials rows (never values)
        String lastSince;

        UplinkRegistry.Response handle(String method, String path, String body) throws Exception {
            if (!online) throw new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "device offline");
            if (method.equals("GET") && path.equals("/v1/sensors")) {
                if (installPolls > 0 && --installPolls == 0) { awInstalled = true; awRunning = true; }
                return ok(Map.of("activitywatch", Map.of("installed", awInstalled, "running", awRunning, "buckets", List.of()),
                        "sandbox", sandboxAvailable ? Map.of("available", true, "kind", "bwrap")
                                : Map.of("available", false, "kind", "none", "error", "bwrap not found"),
                        "operation", installPolls > 0 ? "installing" : ""));
            }
            if (method.equals("GET") && path.equals("/v1/credentials")) return ok(credentials);
            if (method.equals("POST") && path.equals("/v1/sensors/install")) {
                sensorInstalls++;
                installPolls = 2;
                return new UplinkRegistry.Response(202, "application/json", "{}");
            }
            if (method.equals("POST") && path.equals("/v1/collectors")) {
                installs++;
                JsonNode m = JSON.readTree(body).get("manifest");
                ObjectNode contract = JSON.createObjectNode();
                contract.set("schedule", m.get("schedule"));
                contract.set("sources", m.get("sources"));
                contract.set("window", m.get("window"));
                currentHash = Integer.toHexString(contract.toString().hashCode());
                boolean approved = currentHash.equals(approvedHash);
                if (!approved) { active = false; }
                return ok(Map.of("id", "x", "version", installs, "contractHash", currentHash, "approved", approved));
            }
            if (path.endsWith("/test")) {
                boolean pass = testOutcomes.isEmpty() || testOutcomes.remove(0);
                return ok(Map.of("passed", pass, "sandbox", "bwrap", "cases", List.of(
                        Map.of("name", "busy-day", "passed", pass, "expected", 47, "got", pass ? 47 : 12),
                        Map.of("name", "empty-day", "passed", true, "expected", 0, "got", 0))));
            }
            if (path.endsWith("/approve")) {
                approves++;
                String h = JSON.readTree(body).path("contractHash").asText();
                if (!h.equals(currentHash)) return new UplinkRegistry.Response(409, "application/json", "{\"error\":\"hash mismatch\"}");
                approvedHash = h;
                return ok(Map.of());
            }
            if (path.endsWith("/run")) {
                runs++;
                if (!currentHash.equals(approvedHash)) return new UplinkRegistry.Response(403, "application/json", "{\"error\":\"not approved\"}");
                return ok(Map.of("value", 47, "unit", "min", "sandbox", "bwrap",
                        "evidence", List.of(Map.of("label", "youtube.com", "value", "40 min"), Map.of("label", "vimeo.com", "value", "7 min"))));
            }
            if (path.endsWith("/activate")) {
                activates++;
                if (!currentHash.equals(approvedHash)) return new UplinkRegistry.Response(403, "application/json", "{\"error\":\"not approved\"}");
                active = true;
                state = "active";
                return ok(Map.of());
            }
            if (method.equals("GET") && path.equals("/v1/collectors")) {
                return ok(List.of(Map.of("id", "COLLECTOR", "state", state)));
            }
            if (path.contains("/readings")) {
                lastSince = path.contains("since=") ? path.substring(path.indexOf("since=") + 6) : null;
                return ok(readings);
            }
            if (method.equals("DELETE")) return new UplinkRegistry.Response(204, "application/json", "");
            return new UplinkRegistry.Response(404, "application/json", "{\"error\":\"no route\"}");
        }

        private static UplinkRegistry.Response ok(Object body) throws Exception {
            return new UplinkRegistry.Response(200, "application/json", JSON.writeValueAsString(body));
        }
    }

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(KPI.class);
        mongoTemplate.dropCollection(KPIProxyCapability.class);
        mongoTemplate.dropCollection(habitTracker.KPI.deploy.KpiDeployment.class);
        reset(generator, runnerClient, selector);
        runner = new FakeRunner();
        when(selector.pick(USER)).thenReturn(Optional.of(DEVICE));
        org.mockito.stubbing.Answer<UplinkRegistry.Response> route = inv -> {
            String path = inv.getArgument(3);
            // the fake has one collector; list rows carry the real id
            UplinkRegistry.Response r = runner.handle(inv.getArgument(2), path, inv.getArgument(4));
            return new UplinkRegistry.Response(r.status(), r.contentType(), r.body().replace("COLLECTOR", collectorIdOf(path)));
        };
        when(runnerClient.call(anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(route);
        when(runnerClient.call(anyString(), anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(route);
        when(generator.runnerAvailable(USER)).thenReturn(true);
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "Sum YouTube watch time from ActivityWatch web events", "Minutes of YouTube per day",
                "ActivityWatch on your computer", List.of("activitywatch")));
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(bundle(List.of("web")));
    }

    private habitTracker.KPI.deploy.KpiDeployment dep(String kpiId, Integer version) {
        return deploymentRepository.findByKpiIdAndDeviceIdAndCapabilityVersion(kpiId, DEVICE, version).orElseThrow();
    }

    private String lastCollectorId = "kpi-x";

    private String collectorIdOf(String path) {
        if (path.startsWith("/v1/collectors/")) lastCollectorId = path.split("/")[3];
        return lastCollectorId;
    }

    static CollectorBundle bundle(List<String> buckets) {
        ObjectNode m = JSON.createObjectNode();
        m.put("schedule", "daily 00:10").put("window", "1d").put("unit", "min").put("aggregation", "sum");
        ObjectNode src = m.putArray("sources").addObject().put("type", "activitywatch");
        buckets.forEach(src.putArray("buckets")::add);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("collector.py", "import json, sys, argparse\nfrom datetime import datetime\nprint(json.dumps({'value': 0}))\n");
        files.put("fixtures/busy-day/inputs/aw/web.json", "[{\"timestamp\":\"2026-01-01T10:00:00Z\",\"duration\":2820,\"data\":{\"url\":\"https://youtube.com\"}}]");
        files.put("fixtures/busy-day/expected.json", "{\"value\": 47}");
        files.put("fixtures/empty-day/inputs/aw/web.json", "[]");
        files.put("fixtures/empty-day/expected.json", "{\"value\": 0}");
        return new CollectorBundle(m, files);
    }

    private KPI kpi() {
        return kpiRepository.save(KPI.builder().name("YouTube").userId(USER).active(true).higherIsBetter(false).build());
    }

    private CapabilityDTO proposeAndApprove(KPI kpi) {
        CapabilityDTO p = service.proposeAs(USER, kpi.getName(), "YouTube time from my laptop");
        assertEquals(CapabilityPlatform.RUNNER, p.platform());
        assertEquals(List.of("activitywatch"), p.declaredCapabilities().get("sources"));
        return service.approveAs(USER, kpi.getName(), p.id());
    }

    /** Runs the happy path up to SHADOW (live). */
    private KPIProxyCapability live(KPI kpi) {
        CapabilityDTO c = proposeAndApprove(kpi);
        CapabilityDTO a = service.approveContractAs(USER, kpi.getName(), c.id());
        service.confirmAs(USER, kpi.getName(), a.id());
        return capabilityRepository.findById(c.id()).orElseThrow();
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void happyPath_contractThenDryRunThenConfirmThenShadowThenPull() {
        KPI kpi = kpi();
        CapabilityDTO built = proposeAndApprove(kpi);
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, built.status());
        assertEquals("Reads ActivityWatch browser activity · no internet · runs daily at 00:10", built.contractText());
        assertEquals(2, built.collectorTests().size());
        assertEquals(1, built.attempts());
        assertEquals(0, runner.approves, "nothing approved before the user says so");
        assertEquals(0, runner.runs, "no real data read before the contract is approved");

        CapabilityDTO confirm = service.approveContractAs(USER, "YouTube", built.id());
        assertEquals(CapabilityStatus.AWAITING_CONFIRM, confirm.status());
        assertEquals(47.0, confirm.sampleValue());
        assertEquals("47 min", confirm.sampleText());
        assertEquals(2, confirm.sampleEvidence().size());
        assertEquals(1, runner.runs);
        assertEquals(0, runner.activates, "not scheduled before the user confirmed the number");

        CapabilityDTO shadow = service.confirmAs(USER, "YouTube", built.id());
        assertEquals(CapabilityStatus.SHADOW, shadow.status());
        assertEquals(ProxyType.RUNNER_COLLECTOR, kpiRepository.findById(kpi.getId()).orElseThrow().getProxyType());
        assertEquals(1, runner.activates);
        assertTrue(runner.active);
        KPIProxyCapability stored = capabilityRepository.findById(built.id()).orElseThrow();
        assertEquals(java.util.List.of(DEVICE), stored.getTargetDeviceIds());
        habitTracker.KPI.deploy.KpiDeployment storedDep = dep(kpi.getId(), stored.getVersion());
        assertEquals("kpi-" + kpi.getId(), storedDep.getCollectorId());
        assertEquals(runner.approvedHash, stored.getApprovedContractHash());
        assertTrue(storedDep.isActivated());

        // readings pull: two days, one of them recorded twice (newest wins); stored as SHADOW-flagged
        LocalDate d1 = LocalDate.now().minusDays(2), d2 = LocalDate.now().minusDays(1);
        runner.readings.add(reading(d1, 30, "2026-11-03T00:10:00Z"));
        runner.readings.add(reading(d2, 40, "2026-11-04T00:10:00Z"));
        runner.readings.add(reading(d2, 41, "2026-11-04T00:20:00Z"));
        RunnerCollectorProvider.PullResult r = provider.pull(kpiRepository.findById(kpi.getId()).orElseThrow());
        assertEquals(RunnerCollectorProvider.Outcome.PULLED, r.outcome());
        assertEquals(2, r.stored());
        List<Document> data = mongoTemplate.findAll(Document.class, new KPICollectionNameUtil().toCollectionName(kpi.getId()));
        assertEquals(2, data.size());
        Document day2 = data.stream().filter(d -> ((Number) d.get("value")).doubleValue() == 41.0).findFirst().orElseThrow();
        assertEquals("SHADOW", day2.get("flag"));
        assertEquals(true, day2.get("pending"));
        assertEquals("2026-11-04T00:20:00Z", dep(kpi.getId(), stored.getVersion()).getReadingsSince());

        // next pull sends the cursor
        runner.readings.clear();
        provider.pull(kpiRepository.findById(kpi.getId()).orElseThrow());
        assertEquals("2026-11-04T00%3A20%3A00Z", runner.lastSince);

        assertEquals(1, lifecycle.promoteDueShadows(LocalDate.now().plusDays(8)));
        assertEquals(CapabilityStatus.ACTIVE, capabilityRepository.findById(built.id()).orElseThrow().getStatus());
    }

    private static Map<String, Object> reading(LocalDate day, double value, String recordedAt) {
        OffsetDateTime from = day.atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        return Map.of("from", from.toString(), "to", from.plusDays(1).toString(), "value", value, "unit", "min",
                "recordedAt", recordedAt);
    }

    // ── repair: contract unchanged vs changed ─────────────────────────────────

    @Test
    void repair_sameContract_staysApproved_changedContract_needsReapproval() {
        KPI kpi = kpi();
        live(kpi);
        int approvesBefore = runner.approves;

        // same manifest contract → no new approval, goes straight back live (SHADOW: the old one was still on trial)
        KPI broken = kpiRepository.findById(kpi.getId()).orElseThrow();
        broken.setProxyStatus(ProxyStatus.NEEDS_REPAIR);
        kpiRepository.save(broken);
        KPIProxyCapability same = service.repairFor(USER, broken, false);
        assertEquals(CapabilityStatus.SHADOW, same.getStatus());
        assertEquals(approvesBefore, runner.approves, "unchanged contract is never re-approved by the hub");
        assertEquals(ProxyStatus.ACTIVE, kpiRepository.findById(kpi.getId()).orElseThrow().getProxyStatus());
        assertTrue(runner.active, "new version activated");

        // wider contract (adds the window bucket) → AWAITING_CONTRACT, the runner is never asked to approve it
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(bundle(List.of("web", "window")));
        broken = kpiRepository.findById(kpi.getId()).orElseThrow();
        broken.setProxyStatus(ProxyStatus.NEEDS_REPAIR);
        kpiRepository.save(broken);
        KPIProxyCapability changed = service.repairFor(USER, broken, true);
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, changed.getStatus());
        assertTrue(changed.getRepair());
        assertEquals(approvesBefore, runner.approves);
        assertNotEquals(changed.getContractHash(), changed.getApprovedContractHash());
        CapabilityDTO dto = CapabilityDTO.from(changed);
        assertEquals("Reads ActivityWatch browser activity + window · no internet · runs daily at 00:10", dto.contractText());
        assertTrue(dto.repair());

        // the user approves → dry run → live without a second number check (repair)
        CapabilityDTO after = service.approveContractAs(USER, kpi.getName(), changed.getId());
        assertTrue(after.status() == CapabilityStatus.SHADOW || after.status() == CapabilityStatus.ACTIVE);
        assertEquals(approvesBefore + 1, runner.approves);
    }

    // ── sandbox unavailable / sensor missing ──────────────────────────────────

    @Test
    void sandboxUnavailable_stopsBeforeGenerating() {
        runner.sandboxAvailable = false;
        KPI kpi = kpi();
        CapabilityDTO c = proposeAndApprove(kpi);
        assertEquals(CapabilityStatus.TEST_FAILED, c.status());
        assertTrue(c.failureReason().startsWith(RunnerCollectorPipeline.SANDBOX_UNAVAILABLE), c.failureReason());
        assertTrue(c.failureReason().contains("bwrap not found"));
        assertEquals(0, c.attempts());
        verify(generator, never()).generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any());
        assertEquals(0, runner.installs);
    }

    @Test
    void activityWatchMissing_waitsForInstall_thenContinues() {
        runner.awInstalled = false;
        runner.awRunning = false;
        KPI kpi = kpi();
        CapabilityDTO c = proposeAndApprove(kpi);
        assertEquals(CapabilityStatus.AWAITING_SENSOR, c.status());
        assertTrue(c.failureReason().startsWith("ActivityWatch isn't running on"), c.failureReason());
        verify(generator, never()).generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any());

        CapabilityDTO after = service.installSensorAs(USER, kpi.getName(), c.id());
        assertEquals(1, runner.sensorInstalls);
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, after.status());
    }

    // ── fixture failure → repair loop ─────────────────────────────────────────

    @Test
    void fixtureFailure_isFedBack_andCountsAsAttempt() {
        runner.testOutcomes.addAll(List.of(false, true));
        KPI kpi = kpi();
        CapabilityDTO c = proposeAndApprove(kpi);
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, c.status());
        assertEquals(2, c.attempts());
        verify(generator).generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.startsWith("Fixture test failed: case busy-day: expected 47, got 12"));
    }

    @Test
    void staticCheckFailure_neverReachesTheRunner_whenAllAttemptsFail() {
        CollectorBundle bad = bundle(List.of("web"));
        bad.files().put("collector.py", "import socket\nprint(1)\n");
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any())).thenReturn(bad);
        KPI kpi = kpi();
        CapabilityDTO c = proposeAndApprove(kpi);
        assertEquals(CapabilityStatus.TEST_FAILED, c.status());
        assertEquals(CapabilityAgentService.MAX_ATTEMPTS, c.attempts());
        assertTrue(c.failureReason().startsWith("Static check failed: collector.py imports \"socket\""), c.failureReason());
        assertEquals(0, runner.installs);
    }

    // ── runner offline ────────────────────────────────────────────────────────

    @Test
    void runnerOffline_pullIsSkipped_breakerUntouched() {
        KPI kpi = kpi();
        live(kpi);
        runner.online = false;
        KPI fresh = kpiRepository.findById(kpi.getId()).orElseThrow();
        RunnerCollectorProvider.PullResult r = provider.pull(fresh);
        assertEquals(RunnerCollectorProvider.Outcome.SKIPPED_OFFLINE, r.outcome());
        provider.pullDue(); // must not throw either
        KPI after = kpiRepository.findById(kpi.getId()).orElseThrow();
        assertEquals(ProxyStatus.ACTIVE, after.getProxyStatus());
        assertEquals(0, after.getConsecutiveProxyFailures() == null ? 0 : after.getConsecutiveProxyFailures());
        KPIProxyCapability latest = capabilityRepository.findFirstByKpiIdAndUserIdOrderByVersionDesc(kpi.getId(), USER).orElseThrow();
        assertNull(dep(kpi.getId(), latest.getVersion()).getLastPulledOn());
    }

    @Test
    void runnerOffline_duringBuild_isNotAnAttempt() {
        KPI kpi = kpi();
        CapabilityDTO p = service.proposeAs(USER, kpi.getName(), "YouTube time");
        runner.online = false;
        CapabilityDTO c = service.approveAs(USER, kpi.getName(), p.id());
        assertEquals(CapabilityStatus.TEST_FAILED, c.status());
        assertEquals(CapabilityAgentService.NO_RUNNER_REASON, c.failureReason());
        assertEquals(0, c.attempts());
    }

    @Test
    void collectorFailedOnRunner_tripsTheBreakerOncePerDay() {
        KPI kpi = kpi();
        live(kpi);
        runner.state = "failed";
        RunnerCollectorProvider.PullResult r = provider.pull(kpiRepository.findById(kpi.getId()).orElseThrow());
        assertEquals(RunnerCollectorProvider.Outcome.FAILED, r.outcome());
        assertEquals(1, kpiRepository.findById(kpi.getId()).orElseThrow().getConsecutiveProxyFailures());
    }

    static CollectorBundle httpBundle() {
        ObjectNode m = JSON.createObjectNode();
        m.put("schedule", "daily 00:10").put("window", "1d").put("unit", "count").put("aggregation", "sum");
        m.putArray("sources").addObject().put("type", "http").put("credential", "github")
                .put("url", "https://api.github.com/search/issues?q=author:me+created:{from}..{to}").put("why", "my PRs");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("collector.py", "import json, argparse\nprint(json.dumps({'value': 0}))\n");
        files.put("fixtures/busy-day/inputs/http/0.json", "{\"total_count\": 3}");
        files.put("fixtures/busy-day/expected.json", "{\"value\": 3}");
        files.put("fixtures/empty-day/inputs/http/0.json", "{\"total_count\": 0}");
        files.put("fixtures/empty-day/expected.json", "{\"value\": 0}");
        return new CollectorBundle(m, files);
    }

    @Test
    void httpPlan_withTheCredentialOnTheRunner_isGrantedPinnedAndBuilt() {
        runner.credentials.add(Map.of("name", "github", "host", "api.github.com", "auth", "bearer", "updatedAt", "2026-11-08T00:00:00Z"));
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "Count my PRs via the GitHub API", null, null, List.of("http"), List.of(new CredentialRef("github", "api.github.com"))));
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any())).thenReturn(httpBundle());
        KPI kpi = kpi();
        CapabilityDTO p = service.proposeAs(USER, kpi.getName(), "PRs I open per day");
        verify(generator).propose(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(List.of(new CredentialRef("github", "api.github.com"))));
        assertEquals(List.of("http"), p.declaredCapabilities().get("sources"));
        assertEquals(List.of("github@api.github.com"), p.declaredCapabilities().get("credentials"));
        assertEquals(java.util.List.of(DEVICE), capabilityRepository.findById(p.id()).orElseThrow().getTargetDeviceIds());

        CapabilityDTO c = service.approveAs(USER, kpi.getName(), p.id());
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, c.status(), String.valueOf(c.failureReason()));
        verify(generator).generateCollector(anyString(), anyString(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(List.of(new CredentialRef("github", "api.github.com"))), any(), any());
    }

    @Test
    void httpPlan_withoutTheCredential_saysWhichOneToAdd() {
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "Count my PRs via the GitHub API", null, null, List.of("http"), List.of(new CredentialRef("github", "api.github.com"))));
        KPI kpi = kpi();
        CapabilityRejectedException e = assertThrows(CapabilityRejectedException.class,
                () -> service.proposeAs(USER, kpi.getName(), "PRs I open per day"));
        assertTrue(e.getMessage().contains("\"github\" for api.github.com"), e.getMessage());
        assertTrue(e.getMessage().contains("KPI credentials"), e.getMessage());
        assertEquals(0, capabilityRepository.count());
    }

    @Test
    void httpPlan_credentialForAnotherHost_isRejected() {
        runner.credentials.add(Map.of("name", "github", "host", "github.example.com", "auth", "bearer", "updatedAt", "x"));
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "x", null, null, List.of("http"), List.of(new CredentialRef("github", "api.github.com"))));
        KPI kpi = kpi();
        CapabilityRejectedException e = assertThrows(CapabilityRejectedException.class,
                () -> service.proposeAs(USER, kpi.getName(), "x"));
        assertTrue(e.getMessage().contains("is for github.example.com"), e.getMessage());
    }

    @Test
    void proposalWithAnUnknownSource_isRejected() {
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "x", List.of("activitywatch", "http")));
        KPI kpi = kpi();
        assertThrows(CapabilityRejectedException.class, () -> service.proposeAs(USER, kpi.getName(), "x"));
    }
}
