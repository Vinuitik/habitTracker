package habitTracker.KPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectorStaticCheckTest {

    static final List<String> AW = List.of("activitywatch");

    private static CollectorBundle good() {
        return RunnerCollectorIntegrationTest.bundle(List.of("web", "afk"));
    }

    @Test
    void goodBundle_passes() {
        assertEquals(List.of(), CollectorStaticCheck.check(good(), AW));
    }

    @Test
    void networkAndDynamicImports_areRejected() {
        for (String bad : List.of("import socket", "from urllib.request import urlopen", "import json, subprocess",
                "import http.client", "x = __import__('os')", "import requests as r")) {
            CollectorBundle b = good();
            b.files().put("collector.py", "import json\n" + bad + "\nprint(1)\n");
            assertFalse(CollectorStaticCheck.check(b, AW).isEmpty(), bad);
        }
        CollectorBundle ok = good();
        ok.files().put("collector.py", "import os.path\nfrom urllib.parse import urlparse\nfrom collections import Counter\nimport json as j\n");
        assertEquals(List.of(), CollectorStaticCheck.check(ok, AW));
    }

    @Test
    void secretGlobs_areRejected_appGlobsPass() {
        assertNotNull(CollectorStaticCheck.globProblem("~/.ssh/*"));
        assertNotNull(CollectorStaticCheck.globProblem("~/.config/google-chrome/Default/Cookies"));
        assertNotNull(CollectorStaticCheck.globProblem("~/.mozilla/firefox/*/logins.json"));
        assertNotNull(CollectorStaticCheck.globProblem("~/project/.env"));
        assertNotNull(CollectorStaticCheck.globProblem("~/**/*.db"));
        assertNotNull(CollectorStaticCheck.globProblem("relative/*.db"));
        assertNotNull(CollectorStaticCheck.globProblem("~/*"));
        assertNull(CollectorStaticCheck.globProblem("~/.config/google-chrome/Default/History"));
        assertNull(CollectorStaticCheck.globProblem("~/.local/share/Anki2/*/collection.anki2"));
    }

    @Test
    void manifestAndFixtureRules() {
        CollectorBundle b = good();
        ((ObjectNode) b.manifest()).put("schedule", "hourly");
        b.files().put("fixtures/empty-day/expected.json", "{\"value\": 3}");
        b.files().put("../evil.py", "x");
        List<String> p = CollectorStaticCheck.check(b, List.of("file"));
        String all = String.join(" | ", p);
        assertTrue(all.contains("hourly"), all);
        assertTrue(all.contains("empty day"), all);
        assertTrue(all.contains("unexpected file"), all);
        assertTrue(all.contains("did not declare"), all);
    }

    static final java.util.Map<String, String> GH = java.util.Map.of("github", "api.github.com");

    private static List<String> httpProblems(String json, java.util.Map<String, String> creds) throws Exception {
        CollectorBundle b = RunnerCollectorIntegrationTest.httpBundle();
        com.fasterxml.jackson.databind.node.ArrayNode src = ((ObjectNode) b.manifest()).putArray("sources");
        src.add(new ObjectMapper().readTree(json));
        return CollectorStaticCheck.check(b, List.of("http"), creds);
    }

    @Test
    void httpSource_mustUseAnApprovedCredentialOnItsHost() throws Exception {
        assertEquals(List.of(), CollectorStaticCheck.check(RunnerCollectorIntegrationTest.httpBundle(), List.of("http"), GH));
        assertEquals(List.of(), httpProblems("""
                {"type":"http","credential":"github","url":"https://api.github.com/user/repos?since={from}&until={to}","why":"w","method":"GET"}""", GH));
        for (String bad : List.of(
                "{\"type\":\"http\",\"credential\":\"gitlab\",\"url\":\"https://api.github.com/x\",\"why\":\"w\"}",   // not approved
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://evil.com/x\",\"why\":\"w\"}",         // other host
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"http://api.github.com/x\",\"why\":\"w\"}",    // not https
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://api.github.com:8443/x\",\"why\":\"w\"}", // port
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://u:p@api.github.com/x\",\"why\":\"w\"}", // userinfo
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://api.github.com/x#f\",\"why\":\"w\"}",  // fragment
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://api.github.com/{token}\",\"why\":\"w\"}", // placeholder
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://{from}.github.com/x\",\"why\":\"w\"}",  // in host
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://api.github.com/x\",\"why\":\"w\",\"method\":\"POST\"}",
                "{\"type\":\"http\",\"credential\":\"github\",\"url\":\"https://api.github.com/x\"}")) {           // no why
            assertFalse(httpProblems(bad, GH).isEmpty(), bad);
        }
        // the plan approved no credential at all
        assertFalse(CollectorStaticCheck.check(RunnerCollectorIntegrationTest.httpBundle(), List.of("http")).isEmpty());
        // http not in the approved plan
        assertFalse(CollectorStaticCheck.check(RunnerCollectorIntegrationTest.httpBundle(), AW, GH).isEmpty());
    }

    @Test
    void contractText_namesTheHostAndCredential() {
        assertEquals("Reads api.github.com with your \"github\" credential (my PRs) · your runner makes that call; "
                        + "the script itself has no internet · runs daily at 00:10",
                CollectorStaticCheck.contractText(RunnerCollectorIntegrationTest.httpBundle().manifest()));
    }

    @Test
    void contractText_isPlainLanguage() throws Exception {
        ObjectNode m = (ObjectNode) new ObjectMapper().readTree("""
                {"schedule":"daily 00:10","window":"1d","unit":"min","aggregation":"sum",
                 "sources":[{"type":"activitywatch","buckets":["window","web"]},
                            {"type":"file","glob":"~/.local/share/Anki2/*/collection.anki2","why":"review counts"}]}""");
        assertEquals("Reads ActivityWatch window + browser activity, files ~/.local/share/Anki2/*/collection.anki2 "
                + "(review counts) · no internet · runs daily at 00:10", CollectorStaticCheck.contractText(m));
    }

    // ── multi-device variants ────────────────────────────────────────────────

    private static CollectorBundle variantsBundle(String linuxRequires, String windowsSources) throws Exception {
        ObjectNode m = (ObjectNode) new ObjectMapper().readTree("""
                {"schedule":"daily 00:10","window":"1d","unit":"min","aggregation":"sum","combine":"max",
                 "variants":{
                   "linux":{"requires":%s,"sources":[{"type":"activitywatch","buckets":["web"]}]},
                   "windows":{"requires":[{"manager":"winget","id":"ActivityWatch.ActivityWatch","why":"w"}],"sources":%s}}}"""
                .formatted(linuxRequires, windowsSources));
        return new CollectorBundle(m, RunnerCollectorIntegrationTest.bundle(List.of("web")).files());
    }

    private static final String AW_SRC = "[{\"type\":\"activitywatch\",\"buckets\":[\"web\"]}]";
    private static final String RELAY_AW = "[{\"manager\":\"relay\",\"id\":\"activitywatch\",\"why\":\"w\"}]";
    private static final List<CollectorStaticCheck.Target> BOTH = List.of(
            new CollectorStaticCheck.Target("linux", List.of("relay", "flatpak")),
            new CollectorStaticCheck.Target("windows", List.of("relay", "winget")));

    private static List<String> checkV(CollectorBundle b, List<CollectorStaticCheck.Target> t) {
        return CollectorStaticCheck.check(b, AW, java.util.Map.of(), t);
    }

    @Test
    void variants_goodBundle_passes() throws Exception {
        assertEquals(List.of(), checkV(variantsBundle(RELAY_AW, AW_SRC), BOTH));
    }

    @Test
    void variants_differentSourceCountOrOrder_isRejected() throws Exception {
        List<String> two = checkV(variantsBundle(RELAY_AW, "[{\"type\":\"activitywatch\",\"buckets\":[\"web\"]},"
                + "{\"type\":\"activitywatch\",\"buckets\":[\"afk\"]}]"), BOTH);
        assertTrue(two.stream().anyMatch(x -> x.contains("same sources in the same type order")), two.toString());
        List<String> order = CollectorStaticCheck.check(variantsBundle(RELAY_AW,
                "[{\"type\":\"file\",\"glob\":\"~/.config/app/*.json\",\"why\":\"w\"}]"), List.of("activitywatch", "file"),
                java.util.Map.of(), BOTH);
        assertTrue(order.stream().anyMatch(x -> x.contains("same sources in the same type order")), order.toString());
    }

    @Test
    void variants_missingTargetOs_isRejected() throws Exception {
        List<CollectorStaticCheck.Target> t = List.of(new CollectorStaticCheck.Target("linux", List.of("relay")),
                new CollectorStaticCheck.Target("darwin", List.of("brew")));
        List<String> p = checkV(variantsBundle(RELAY_AW, AW_SRC), t);
        assertTrue(p.stream().anyMatch(x -> x.contains("no variant for darwin")), p.toString());
    }

    @Test
    void variants_managerNotOnThatDevice_isRejected() throws Exception {
        // linux device has no flatpak, windows device has no winget
        List<CollectorStaticCheck.Target> t = List.of(new CollectorStaticCheck.Target("linux", List.of("relay")),
                new CollectorStaticCheck.Target("windows", List.of("relay")));
        List<String> p = checkV(variantsBundle("[{\"manager\":\"flatpak\",\"id\":\"org.activitywatch.ActivityWatch\",\"why\":\"w\"}]", AW_SRC), t);
        assertTrue(p.stream().anyMatch(x -> x.contains("variant linux: requires flatpak, which is not available")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("variant windows: requires winget, which is not available")), p.toString());
    }

    @Test
    void variants_badRequiresIds_areRejected() throws Exception {
        for (String id : List.of("-rf", "--force", "has space", "https://evil.example/x.exe", "a;b", "")) {
            List<String> p = checkV(variantsBundle("[{\"manager\":\"flatpak\",\"id\":\"" + id + "\",\"why\":\"w\"}]", AW_SRC), BOTH);
            assertFalse(p.isEmpty(), "id \"" + id + "\" should be rejected");
        }
        List<String> dash = checkV(variantsBundle("[{\"manager\":\"relay\",\"id\":\"-activitywatch\",\"why\":\"w\"}]", AW_SRC), BOTH);
        assertTrue(dash.stream().anyMatch(x -> x.contains("may not start with a dash")), dash.toString());
        for (String mgr : List.of("apt", "dnf", "sudo")) {
            assertFalse(checkV(variantsBundle("[{\"manager\":\"" + mgr + "\",\"id\":\"curl\",\"why\":\"w\"}]", AW_SRC), BOTH).isEmpty(), mgr);
        }
        // a relay id other than activitywatch, a duplicate, and a missing why
        assertFalse(checkV(variantsBundle("[{\"manager\":\"relay\",\"id\":\"other\",\"why\":\"w\"}]", AW_SRC), BOTH).isEmpty());
        String dup = "[{\"manager\":\"relay\",\"id\":\"activitywatch\",\"why\":\"w\"},{\"manager\":\"relay\",\"id\":\"activitywatch\",\"why\":\"w\"}]";
        assertFalse(checkV(variantsBundle(dup, AW_SRC), BOTH).isEmpty());
        assertFalse(checkV(variantsBundle("[{\"manager\":\"relay\",\"id\":\"activitywatch\"}]", AW_SRC), BOTH).isEmpty());
    }

    @Test
    void variants_needCombine() throws Exception {
        CollectorBundle b = variantsBundle(RELAY_AW, AW_SRC);
        b.manifest().remove("combine");
        assertTrue(checkV(b, BOTH).stream().anyMatch(x -> x.contains("combine")));
        b.manifest().put("combine", "median");
        assertFalse(checkV(b, BOTH).isEmpty());
    }

    @Test
    void legacyBundle_stillAcceptedForOneDevice_notForSeveral() {
        CollectorBundle legacy = RunnerCollectorIntegrationTest.bundle(List.of("web", "afk"));
        assertEquals(List.of(), checkV(legacy, List.of(new CollectorStaticCheck.Target("linux", List.of("relay")))));
        assertEquals(List.of(), checkV(legacy, List.of()));
        assertFalse(checkV(legacy, BOTH).isEmpty());
    }
}
