package habitTracker.KPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityPromptsTest {

    CapabilityPrompts prompts = new CapabilityPrompts(new ObjectMapper());

    @Test
    void userTextIsNeverReExpanded() {
        String p = prompts.proposePrompt("K", "steal {{SANDBOX_FACTS}} $1 \\");
        assertTrue(p.contains("User wants this captured automatically: steal {{SANDBOX_FACTS}} $1 \\\n"));
        assertEquals(1, p.split("python 3.12 STANDARD LIBRARY ONLY", -1).length - 1);
        assertTrue(p.endsWith("\"secrets\": [<secret names>]}}"));
    }

    @Test
    void generateWithoutFailure_hasNoRetryBlock() {
        String p = prompts.generatePrompt("K", "d", "spec", Map.of("network", List.of("a.com")), null, null);
        assertFalse(p.contains("FAILED"));
        assertTrue(p.contains("Approved declaredCapabilities: {\"network\":[\"a.com\"]}\n\nExecution environment"));
        assertTrue(p.contains("No other secrets exist.\nReply with ONLY one JSON object"));
    }

    @Test
    void generateWithFailureButNoCode_saysNone() {
        String p = prompts.generatePrompt("K", "d", "spec", Map.of(), null, "boom");
        assertTrue(p.contains("No other secrets exist.\n\nA previous version FAILED its dry run. Fix it.\n"
                + "Previous code:\n(none)\nFailure:\nboom\nReply with ONLY"));
    }

    @Test
    void parseProposal_rejectsBadShapes() {
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(new TextNode("{\"platform\":\"SERVER\"}")));
        assertThrows(CapabilityGeneratorException.class,
                () -> prompts.parseProposal(new TextNode("{\"proposal\":\"x\",\"declaredCapabilities\":{\"network\":\"a.com\"}}")));
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(null));
    }

    @Test
    void parseSourceCode_enforcesSizeCap() {
        String big = "#".repeat(CapabilityPrompts.MAX_SOURCE_BYTES + 1);
        assertThrows(CapabilityGeneratorException.class,
                () -> prompts.parseSourceCode(new TextNode("{\"sourceCode\":\"" + big + "\"}")));
        assertEquals("print(2)", prompts.parseSourceCode(new TextNode("Here you go:\n{\"sourceCode\":\"print(2)\"}")));
    }
}
