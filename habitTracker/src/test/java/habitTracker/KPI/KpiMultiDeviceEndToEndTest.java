package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import habitTracker.KPI.deploy.KpiDeployment;
import habitTracker.KPI.deploy.KpiDeploymentReconciler;
import habitTracker.KPI.deploy.KpiDeploymentRepository;
import habitTracker.KPI.deploy.KpiDeploymentStatus;
import habitTracker.KPI.deploy.KpiDeploymentTarget;
import habitTracker.KPI.deploy.KpiRetireService;
import habitTracker.KPI.deploy.PackageUninstallService;
import habitTracker.KPI.deploy.PendingPackageUninstall;
import habitTracker.KPI.deploy.PendingPackageUninstallRepository;
import habitTracker.relay.Device;
import habitTracker.relay.DeviceRepository;
import habitTracker.relay.OnlineRunnerSelector;
import habitTracker.relay.RunnerClient;
import habitTracker.relay.RunnerConnected;
import habitTracker.relay.RunnerUnavailableException;
import habitTracker.relay.UplinkRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Multi-device KPI end to end over the real services + Mongo (kpi-multidevice.md): TWO fake runners behind the
 * RunnerClient boundary (device A = windows, device B = linux), the LLM stubbed. One KPI is followed from "propose for
 * A and B" through contract approval, going live with B offline, partial days, B connecting + backfill, a redefine that
 * rolls out per device, and a retire with a queued package uninstall.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"kpi.runner.pull-cron=-", "kpi.shadow.promote-cron=-",
                "kpi.deploy.sweep-ms=3600000", "kpi.package-uninstall.sweep-ms=3600000"})
@Testcontainers
class KpiMultiDeviceEndToEndTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @MockitoBean CapabilityGenerator generator;
    @MockitoBean RunnerClient runnerClient;
    @MockitoBean OnlineRunnerSelector selector;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired KPIRepository kpiRepository;
    @Autowired KPIProxyCapabilityRepository capabilities;
    @Autowired KpiDeploymentRepository deployments;
    @Autowired DeviceRepository deviceRepository;
    @Autowired PendingPackageUninstallRepository pendingUninstalls;
    @Autowired CapabilityAgentService service;
    @Autowired RunnerCollectorProvider provider;
    @Autowired KpiDeploymentReconciler reconciler;
    @Autowired KpiRetireService retireService;
    @Autowired PackageUninstallService uninstallService;
    @Autowired DynamicKPIDataRepository dataRepository;
    @Autowired ApplicationEventPublisher events;

    static final ObjectMapper JSON = new ObjectMapper();
    static final String USER = "user-1";
    static final String A = "dev-a", B = "dev-b";

    FakeRunner runnerA, runnerB;

    /** In-memory runner: collectors by id, approve/activate state, readings, requires + package ledger. */
    static final class FakeRunner {
        final String os;
        final List<String> managers;
        boolean online = true;
        /** requires of an approved collector stay "installing" until the test calls finishInstalls(). */
        boolean installsFinished = false;
        final Map<String, Collector> collectors = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> readings = new HashMap<>();
        /** "manager:id" to the collector ids using it */
        final Map<String, Set<String>> packages = new LinkedHashMap<>();
        final List<String> uninstalled = new ArrayList<>();
        int installCalls;

        FakeRunner(String os, List<String> managers) {
            this.os = os;
            this.managers = managers;
        }

        static final class Collector {
            ObjectNode manifest;
            String hash;
            String approvedHash;
            boolean active;
        }

        void finishInstalls() {
            installsFinished = true;
        }

        void addReading(String collectorId, LocalDate day, double value, String recordedAt) {
            OffsetDateTime from = day.atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
            readings.computeIfAbsent(collectorId, k -> new ArrayList<>()).add(Map.of("from", from.toString(),
                    "to", from.plusDays(1).toString(), "value", value, "unit", "min", "recordedAt", recordedAt));
        }

        UplinkRegistry.Response handle(String method, String path, String body) throws Exception {
            if (!online) throw new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "device offline");
            if (method.equals("GET") && path.equals("/v1/runner/info")) {
                return ok(Map.of("os", os, "arch", "amd64", "managers", managers));
            }
            if (method.equals("GET") && path.equals("/v1/sensors")) {
                return ok(Map.of("activitywatch", Map.of("installed", true, "running", true, "buckets", List.of()),
                        "sandbox", Map.of("available", true, "kind", "bwrap"), "operation", ""));
            }
            if (method.equals("GET") && path.equals("/v1/credentials")) return ok(List.of());
            if (method.equals("GET") && path.equals("/v1/packages")) {
                List<Map<String, Object>> out = new ArrayList<>();
                packages.forEach((k, users) -> out.add(Map.of("manager", k.split(":")[0], "id", k.split(":")[1],
                        "installedByRelay", true, "usedBy", List.copyOf(users), "state", "installed")));
                return ok(out);
            }
            if (method.equals("POST") && path.equals("/v1/packages/uninstall")) {
                JsonNode b = JSON.readTree(body);
                String key = b.path("manager").asText() + ":" + b.path("id").asText();
                Set<String> users = packages.get(key);
                if (users == null) return new UplinkRegistry.Response(400, "application/json", "{\"error\":\"not installed by Relay\"}");
                if (!users.isEmpty()) return new UplinkRegistry.Response(409, "application/json", "{\"error\":\"in use\"}");
                packages.remove(key);
                uninstalled.add(key);
                return new UplinkRegistry.Response(202, "application/json", "{}");
            }
            if (method.equals("POST") && path.equals("/v1/collectors")) {
                installCalls++;
                JsonNode req = JSON.readTree(body);
                String id = req.path("id").asText();
                ObjectNode m = (ObjectNode) req.get("manifest");
                assertFalse(m.has("variants"), "the runner only ever gets its own resolved manifest");
                assertFalse(m.has("combine"), "combine never reaches the runner");
                ObjectNode contract = JSON.createObjectNode();
                contract.set("schedule", m.get("schedule"));
                contract.set("sources", m.get("sources"));
                contract.set("window", m.get("window"));
                contract.set("requires", m.path("requires"));
                Collector c = collectors.computeIfAbsent(id, k -> new Collector());
                String hash = Integer.toHexString(contract.toString().hashCode());
                if (!hash.equals(c.hash)) c.active = false;
                c.manifest = m;
                c.hash = hash;
                return ok(Map.of("id", id, "version", installCalls, "contractHash", hash, "approved", hash.equals(c.approvedHash)));
            }
            String[] seg = path.split("\\?")[0].split("/");   // "", v1, collectors, <id>, <action>
            if (seg.length >= 4 && seg[2].equals("collectors")) {
                Collector c = collectors.get(seg[3]);
                String action = seg.length > 4 ? seg[4] : "";
                if (c == null) return new UplinkRegistry.Response(404, "application/json", "{\"error\":\"no such collector\"}");
                switch (method + " " + action) {
                    case "POST test":
                        return ok(Map.of("passed", true, "sandbox", "bwrap", "cases", List.of(
                                Map.of("name", "busy-day", "passed", true, "expected", 47, "got", 47),
                                Map.of("name", "empty-day", "passed", true, "expected", 0, "got", 0))));
                    case "POST approve": {
                        String h = JSON.readTree(body).path("contractHash").asText();
                        if (!h.equals(c.hash)) return new UplinkRegistry.Response(409, "application/json", "{\"error\":\"hash mismatch\"}");
                        c.approvedHash = h;
                        JsonNode req = c.manifest.path("requires");
                        for (JsonNode r : req) {
                            packages.computeIfAbsent(r.path("manager").asText() + ":" + r.path("id").asText(), k -> new TreeSet<>()).add(seg[3]);
                        }
                        return ok(req.size() > 0 && !installsFinished ? Map.of("state", "installing") : Map.of());
                    }
                    case "POST run":
                        if (!Objects.equals(c.hash, c.approvedHash)) return new UplinkRegistry.Response(403, "application/json", "{\"error\":\"not approved\"}");
                        return ok(Map.of("value", 47, "unit", "min", "sandbox", "bwrap",
                                "evidence", List.of(Map.of("label", "youtube.com", "value", "47 min"))));
                    case "POST activate":
                        if (!Objects.equals(c.hash, c.approvedHash)) return new UplinkRegistry.Response(403, "application/json", "{\"error\":\"not approved\"}");
                        c.active = true;
                        return ok(Map.of());
                    case "GET readings": {
                        String since = path.contains("since=") ? URLDecoder.decode(path.substring(path.indexOf("since=") + 6), StandardCharsets.UTF_8) : null;
                        List<Map<String, Object>> all = readings.getOrDefault(seg[3], List.of());
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (Map<String, Object> r : all) {
                            if (since == null || OffsetDateTime.parse((String) r.get("recordedAt")).isAfter(OffsetDateTime.parse(since))) out.add(r);
                        }
                        return ok(out);
                    }
                    case "DELETE ":
                        collectors.remove(seg[3]);
                        packages.values().forEach(u -> u.remove(seg[3]));
                        return new UplinkRegistry.Response(204, "application/json", "");
                    default:
                        break;
                }
            }
            if (method.equals("GET") && path.equals("/v1/collectors")) {
                List<Map<String, Object>> out = new ArrayList<>();
                collectors.forEach((id, c) -> {
                    boolean needs = c.manifest.path("requires").size() > 0;
                    out.add(Map.of("id", id, "state", c.active ? "active" : "pending",
                            "requiresState", needs && !installsFinished ? "installing" : "none"));
                });
                return ok(out);
            }
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
        mongoTemplate.dropCollection(KpiDeployment.class);
        mongoTemplate.dropCollection(Device.class);
        mongoTemplate.dropCollection(PendingPackageUninstall.class);
        mongoTemplate.dropCollection(habitTracker.KPI.deploy.KpiDeviceReading.class);
        reset(generator, runnerClient, selector);
        runnerA = new FakeRunner("windows", List.of("relay", "winget"));
        runnerB = new FakeRunner("linux", List.of("relay", "flatpak"));
        deviceRepository.save(Device.builder().id(A).userId(USER).name("Desktop").tokenHash("ta").createdAt(Instant.now()).build());
        deviceRepository.save(Device.builder().id(B).userId(USER).name("Laptop").tokenHash("tb").createdAt(Instant.now()).build());
        when(selector.pick(USER)).thenReturn(Optional.of(A));
        org.mockito.stubbing.Answer<UplinkRegistry.Response> route = inv -> {
            String device = inv.getArgument(1);
            FakeRunner r = A.equals(device) ? runnerA : B.equals(device) ? runnerB : null;
            assertNotNull(r, "unknown device " + device);
            return r.handle(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4));
        };
        when(runnerClient.call(anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(route);
        when(runnerClient.call(anyString(), anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(route);
        when(generator.runnerAvailable(USER)).thenReturn(true);
        when(generator.propose(anyString(), anyString(), anyString(), any())).thenReturn(new CapabilityGenerator.Proposal(
                "Add up the minutes of YouTube on each computer", "Minutes of YouTube per day", "ActivityWatch", List.of("activitywatch")));
    }

    /** variants bundle: windows installs via winget, linux via Relay's own ActivityWatch. */
    static CollectorBundle variantsBundle(String combine, String why) {
        ObjectNode m = JSON.createObjectNode();
        m.put("schedule", "daily 00:10").put("window", "1d").put("unit", "min").put("aggregation", "sum").put("combine", combine);
        ObjectNode variants = m.putObject("variants");
        ObjectNode win = variants.putObject("windows");
        win.putArray("requires").addObject().put("manager", "winget").put("id", "ActivityWatch.ActivityWatch").put("why", "tracks app usage");
        win.putArray("sources").addObject().put("type", "activitywatch").putArray("buckets").add("web");
        ObjectNode lin = variants.putObject("linux");
        lin.putArray("requires").addObject().put("manager", "relay").put("id", "activitywatch").put("why", "tracks app usage");
        lin.putArray("sources").addObject().put("type", "activitywatch").putArray("buckets").add("web");
        return new CollectorBundle(m, RunnerCollectorIntegrationTest.bundle(List.of("web")).files(), why);
    }

    // helpers

    private KpiDeployment row(KPI kpi, String device, int version) {
        return deployments.findByKpiIdAndDeviceIdAndCapabilityVersion(kpi.getId(), device, version).orElseThrow();
    }

    private void await(String what, BooleanSupplier cond) {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            reconciler.sweep();   // synchronous; the async kick from the event does the same work
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("timed out waiting for: " + what + " | rows: " + deployments.findAll().stream().map(d -> d.getDeviceId() + " v" + d.getCapabilityVersion() + " " + d.getTarget() + "/" + d.getStatus() + " att=" + d.getAttempts() + " err=" + d.getLastError()).toList());
    }

    private KPIData day(KPI kpi, LocalDate d) {
        return dataRepository.findByDate(d, new KPICollectionNameUtil().toCollectionName(kpi.getId())).orElse(null);
    }

    private void pull(KPI kpi) {
        provider.pull(kpiRepository.findById(kpi.getId()).orElseThrow());
    }

    private KPIProxyCapability build(KPI kpi, CapabilityDTO proposed) {
        CapabilityDTO built = service.approveAs(USER, kpi.getName(), proposed.id());
        assertEquals(CapabilityStatus.AWAITING_CONTRACT, built.status());
        return capabilities.findById(built.id()).orElseThrow();
    }

    // the scenario

    @Test
    void twoDevices_fromProposalToRetire() {
        KPI kpi = kpiRepository.save(KPI.builder().name("YouTube").userId(USER).active(true).higherIsBetter(false).build());
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(variantsBundle("sum", "Each computer counts its own watching, so the minutes are added up."));

        runnerA.finishInstalls();   // winget is quick on the Desktop; the Laptop's program install is stepped by hand below

        // 1. propose for both devices, build, contract
        CapabilityDTO proposed = service.proposeAs(USER, "YouTube", "YouTube minutes on my computers", List.of(A, B));
        assertEquals(CapabilityStatus.PROPOSED, proposed.status());
        assertEquals(List.of(A, B), capabilities.findById(proposed.id()).orElseThrow().getTargetDeviceIds());
        KPIProxyCapability v1 = build(kpi, proposed);
        assertEquals(List.of(A, B), v1.getTargetDeviceIds());
        assertFalse(CollectorBundle.fromJson(v1.getCollectorBundle()).isLegacy(), "a variants bundle is stored");
        assertEquals("sum", v1.getCombine());
        assertEquals("Each computer counts its own watching, so the minutes are added up.", v1.getCombineWhy());
        assertEquals(1, runnerA.collectors.size(), "the build runs on the proposing device only");
        assertEquals(0, runnerB.collectors.size());
        assertEquals("kpi-" + kpi.getId() + "-v1", row(kpi, A, 1).getCollectorId());
        assertEquals("windows", row(kpi, A, 1).getOs());
        assertEquals(KpiDeploymentStatus.PENDING, row(kpi, A, 1).getStatus(), "nothing is live before the user approves");

        // contract approved + number confirmed, but B went offline in between
        service.approveContractAs(USER, "YouTube", v1.getId());
        runnerB.online = false;
        CapabilityDTO shadow = service.confirmAs(USER, "YouTube", v1.getId());
        assertEquals(CapabilityStatus.SHADOW, shadow.status());
        assertEquals(ProxyType.RUNNER_COLLECTOR, kpiRepository.findById(kpi.getId()).orElseThrow().getProxyType());

        // 2. B offline: A converges to ACTIVE, B waits with no attempt counted
        await("A active", () -> row(kpi, A, 1).getStatus() == KpiDeploymentStatus.ACTIVE);
        KpiDeployment b1 = row(kpi, B, 1);
        assertEquals(KpiDeploymentStatus.PENDING, b1.getStatus());
        assertEquals(KpiDeploymentTarget.ACTIVE, b1.getTarget());
        assertEquals(0, b1.getAttempts(), "an offline device is not a failed attempt");
        assertNull(b1.getLastError());
        assertTrue(runnerA.collectors.get("kpi-" + kpi.getId() + "-v1").active);
        assertEquals(0, runnerB.collectors.size());
        assertTrue(runnerA.packages.containsKey("winget:ActivityWatch.ActivityWatch"));

        // 3. readings on A only: a partial day (1 of 2)
        String c1 = "kpi-" + kpi.getId() + "-v1";
        LocalDate d1 = LocalDate.now().minusDays(2), d2 = LocalDate.now().minusDays(1);
        runnerA.addReading(c1, d1, 30, "2026-11-03T00:10:00Z");
        runnerA.addReading(c1, d2, 20, "2026-11-04T00:10:00Z");
        pull(kpi);
        KPIData p1 = day(kpi, d1);
        assertNotNull(p1);
        assertEquals(30.0, p1.getValue());
        assertEquals(1, p1.getDevicesReported());
        assertEquals(2, p1.getDevicesExpected());
        assertEquals(20.0, day(kpi, d2).getValue());
        assertEquals(1, day(kpi, d2).getDevicesReported());
        assertEquals(KpiDeploymentStatus.PENDING, row(kpi, B, 1).getStatus(), "pulling never counts B");
        assertEquals(0, row(kpi, B, 1).getAttempts());

        // 4. B connects: installs, waits for its program, then goes ACTIVE
        runnerB.online = true;
        events.publishEvent(new RunnerConnected(USER, B));
        await("B installing", () -> row(kpi, B, 1).getStatus() == KpiDeploymentStatus.INSTALLING);
        assertEquals("linux", row(kpi, B, 1).getOs());
        assertEquals(1, runnerB.collectors.size());
        FakeRunner.Collector onB = runnerB.collectors.get(c1);
        assertEquals("activitywatch", onB.manifest.path("requires").get(0).path("id").asText(), "B got the linux variant");
        assertFalse(onB.active, "not scheduled while its program is still installing");
        runnerB.finishInstalls();
        await("B active", () -> row(kpi, B, 1).getStatus() == KpiDeploymentStatus.ACTIVE);
        assertTrue(runnerB.collectors.get(c1).active);
        assertEquals(0, row(kpi, B, 1).getAttempts());

        // 5. B backfills d1: combined value updated (sum), both devices reported
        runnerB.addReading(c1, d1, 45, "2026-11-05T09:00:00Z");
        pull(kpi);
        KPIData merged = day(kpi, d1);
        assertEquals(75.0, merged.getValue(), "30 on A + 45 on B");
        assertEquals(2, merged.getDevicesReported());
        assertEquals(2, merged.getDevicesExpected());
        assertEquals(20.0, day(kpi, d2).getValue(), "d2 has no B reading yet: unchanged");
        assertEquals(1, day(kpi, d2).getDevicesReported());

        // 6. redefine: v2 (combine max), built on A, approved, goes live while both devices are offline
        when(generator.generateCollector(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(variantsBundle("max", "The same watching shows up on both computers, so the highest value is kept."));
        CapabilityDTO re = service.redefineAs(USER, "YouTube", null, null);
        assertEquals(2, re.version());
        KPIProxyCapability v2 = build(kpi, re);
        assertEquals("max", v2.getCombine());
        service.approveContractAs(USER, "YouTube", v2.getId());
        runnerA.online = false;
        runnerB.online = false;
        service.confirmAs(USER, "YouTube", v2.getId());
        String c2 = "kpi-" + kpi.getId() + "-v2";
        assertEquals(2, deployments.findByKpiIdAndCapabilityVersion(kpi.getId(), 2).size(), "a v2 row per target device");
        assertEquals(KpiDeploymentStatus.ACTIVE, row(kpi, A, 1).getStatus());
        assertEquals(KpiDeploymentTarget.ACTIVE, row(kpi, A, 1).getTarget(), "v1 keeps running until v2 runs on that device");
        assertEquals(KpiDeploymentTarget.ACTIVE, row(kpi, B, 1).getTarget());
        assertTrue(runnerA.collectors.containsKey(c1) && runnerB.collectors.containsKey(c1));
        assertTrue(runnerA.collectors.containsKey(c2), "v2 was installed on A while building");

        // A comes back: v2 runs there, so v1 is deleted on A only
        runnerA.online = true;
        LocalDate d3 = LocalDate.now();
        runnerB.addReading(c1, d3, 11, "2026-11-06T00:10:00Z");
        runnerA.addReading(c2, d3, 7, "2026-11-06T00:20:00Z");
        LocalDate d4 = LocalDate.now().minusDays(3);   // an unpulled reading still sitting on A's OLD collector at handoff
        runnerA.addReading(c1, d4, 5, "2026-11-06T00:05:00Z");
        assertNull(day(kpi, d4));
        events.publishEvent(new RunnerConnected(USER, A));
        await("A runs v2 and v1 is gone from A", () -> row(kpi, A, 2).getStatus() == KpiDeploymentStatus.ACTIVE
                && row(kpi, A, 1).getStatus() == KpiDeploymentStatus.REMOVED);
        assertFalse(runnerA.collectors.containsKey(c1), "old collector deleted on A once v2 runs there");
        assertNotNull(day(kpi, d4), "the old collector was drained before it was deleted");
        assertEquals(5.0, day(kpi, d4).getValue());
        assertTrue(runnerA.collectors.get(c2).active);
        assertEquals(KpiDeploymentTarget.REMOVED, row(kpi, A, 1).getTarget());
        assertEquals(KpiDeploymentStatus.PENDING, row(kpi, B, 2).getStatus());
        assertEquals(KpiDeploymentTarget.ACTIVE, row(kpi, B, 1).getTarget(), "B's v1 is kept: its v2 is not ACTIVE yet");
        assertTrue(runnerB.collectors.containsKey(c1));

        // B is reachable for a pull only: its v1 collector is still read while v2 is not running there
        runnerB.online = true;
        pull(kpi);
        KPIData today = day(kpi, d3);
        assertNotNull(today, "the pull read A's v2 collector and B's v1 collector");
        assertEquals(11.0, today.getValue(), "max(A v2 = 7, B v1 = 11)");
        assertEquals(2, today.getDevicesReported());

        // B's v2 comes up; only then the old collector on B is deleted
        events.publishEvent(new RunnerConnected(USER, B));
        await("B v2 active, v1 removed", () -> row(kpi, B, 2).getStatus() == KpiDeploymentStatus.ACTIVE
                && row(kpi, B, 1).getStatus() == KpiDeploymentStatus.REMOVED);
        assertFalse(runnerB.collectors.containsKey(c1));
        assertTrue(runnerB.collectors.containsKey(c2));

        // 7. retire: everything REMOVED, collectors gone on both runners
        KpiRetireService.Retired retired = retireService.retire(USER, kpi.getId()).orElseThrow();
        assertEquals(2, retired.packages().size(), "winget on Desktop and Relay's ActivityWatch on Laptop");
        await("all rows removed", () -> deployments.findByKpiId(kpi.getId()).stream()
                .allMatch(d -> d.getTarget() == KpiDeploymentTarget.REMOVED && d.getStatus() == KpiDeploymentStatus.REMOVED));
        assertTrue(runnerA.collectors.isEmpty(), runnerA.collectors.keySet().toString());
        assertTrue(runnerB.collectors.isEmpty(), runnerB.collectors.keySet().toString());
        assertEquals(ProxyType.NONE, kpiRepository.findById(kpi.getId()).orElseThrow().getProxyType());
        assertTrue(capabilities.findByKpiIdAndUserId(kpi.getId(), USER).stream().allMatch(c ->
                c.getStatus() == CapabilityStatus.RETIRED || c.getStatus() == CapabilityStatus.DISABLED),   // v1 was disabled when v2 went live
                capabilities.findByKpiIdAndUserId(kpi.getId(), USER).stream().map(c -> c.getVersion() + ":" + c.getStatus()).toList().toString());

        // 8. a package uninstall for an offline device is queued, then sent when it reconnects
        runnerA.online = false;
        var results = uninstallService.request(USER, kpi.getId(), List.of(
                new PackageUninstallService.Target("winget", "ActivityWatch.ActivityWatch", List.of(A))));
        assertEquals(PackageUninstallService.Status.QUEUED, results.get(0).status());
        assertEquals(1, pendingUninstalls.findByDeviceId(A).size());
        assertTrue(runnerA.packages.containsKey("winget:ActivityWatch.ActivityWatch"), "nothing sent to an offline device");
        runnerA.online = true;
        events.publishEvent(new RunnerConnected(USER, A));
        await("uninstall sent", () -> runnerA.uninstalled.contains("winget:ActivityWatch.ActivityWatch"));
        await("queue emptied", () -> pendingUninstalls.findByDeviceId(A).isEmpty());
        assertFalse(runnerA.packages.containsKey("winget:ActivityWatch.ActivityWatch"));
    }
}
