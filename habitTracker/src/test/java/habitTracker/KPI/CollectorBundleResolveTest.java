package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CollectorBundleResolveTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CollectorBundle bundle(String manifest) throws Exception {
        return new CollectorBundle((com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(manifest),
                Map.of("collector.py", "print(1)"));
    }

    private static final String VARIANTS = """
            {"schedule":"daily 23:30","window":"1d","unit":"hours","aggregation":"sum","combine":"max",
             "variants":{
               "windows":{"requires":[{"manager":"winget","id":"ActivityWatch.ActivityWatch","why":"w"}],"sources":[{"type":"activitywatch","bucket":"win"}]},
               "linux":{"sources":[{"type":"activitywatch","bucket":"lin"}]}}}""";

    @Test
    void resolve_picksTheOsVariant_andDropsCombineAndVariants() throws Exception {
        CollectorBundle b = bundle(VARIANTS);
        assertFalse(b.isLegacy());
        assertEquals("max", b.combine());
        JsonNode win = b.resolve("windows");
        assertEquals("win", win.path("sources").get(0).path("bucket").asText());
        assertEquals("winget", win.path("requires").get(0).path("manager").asText());
        assertEquals("daily 23:30", win.path("schedule").asText());
        assertEquals("hours", win.path("unit").asText());
        assertFalse(win.has("variants"));
        assertFalse(win.has("combine"));
        JsonNode lin = b.resolve("linux");
        assertEquals("lin", lin.path("sources").get(0).path("bucket").asText());
        assertFalse(lin.has("requires"), "no requires in the linux variant");
        assertEquals(java.util.List.of("activitywatch"), b.sourceTypes());
    }

    @Test
    void resolve_missingOrNullOs_throws() throws Exception {
        CollectorBundle b = bundle(VARIANTS);
        assertThrows(IllegalArgumentException.class, () -> b.resolve("darwin"));
        assertThrows(IllegalArgumentException.class, () -> b.resolve(null));
    }

    @Test
    void resolve_legacyBundle_isItsOwnVariantForAnyOs() throws Exception {
        CollectorBundle b = bundle("{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"min\",\"aggregation\":\"sum\","
                + "\"sources\":[{\"type\":\"file\",\"path\":\"/x\"}]}");
        assertTrue(b.isLegacy());
        assertTrue(b.variants().isEmpty());
        assertEquals("sum", b.combine());
        assertEquals(b.manifest(), b.resolve("windows"));
        assertEquals(b.manifest(), b.resolve(null));
        assertEquals(java.util.List.of("file"), b.sourceTypes());
    }

    @Test
    void withVariantFor_rewritesLegacyIntoOneVariant_andResolvesBackToTheSameManifest() throws Exception {
        CollectorBundle legacy = bundle("{\"schedule\":\"daily 23:30\",\"window\":\"1d\",\"unit\":\"min\",\"aggregation\":\"sum\","
                + "\"sources\":[{\"type\":\"file\",\"path\":\"/x\"}]}");
        CollectorBundle v = legacy.withVariantFor("linux", "sum");
        assertFalse(v.isLegacy());
        assertEquals(java.util.Set.of("linux"), v.variants().keySet());
        assertEquals(legacy.manifest(), v.resolve("linux"), "what the runner receives is unchanged");
        assertSame(v, v.withVariantFor("windows", "sum"), "already variants: untouched");
        assertEquals(v.toJson(), CollectorBundle.fromJson(v.toJson()).toJson());
    }
}
