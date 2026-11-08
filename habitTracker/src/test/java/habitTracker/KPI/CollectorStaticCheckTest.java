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
}
