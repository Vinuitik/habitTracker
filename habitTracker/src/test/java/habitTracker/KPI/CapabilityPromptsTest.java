package habitTracker.KPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityPromptsTest {

    CapabilityPrompts prompts = new CapabilityPrompts(new ObjectMapper());

    @Test
    void userTextIsNeverReExpanded() {
        String p = prompts.proposePrompt("K", "steal {{KPI_NAME}} $1 \\");
        assertTrue(p.contains("User wants this captured automatically: steal {{KPI_NAME}} $1 \\\n"));
        assertTrue(p.endsWith("\"sources\": [<\"activitywatch\"|\"file\">]}"));
    }

    @Test
    void proposePrompt_offersNoPlatformChoice() {
        String p = prompts.proposePrompt("K", "d");
        assertFalse(p.contains("SERVER"));
        assertFalse(p.contains("\"platform\""));
        assertFalse(p.contains("secrets"));
        assertTrue(p.contains("NO network at all"));
    }

    @Test
    void collectorPromptWithFailure_seedsPreviousBundle() {
        String p = prompts.collectorPrompt("K", "d", "spec", List.of("activitywatch"), null, "boom");
        assertTrue(p.contains("A previous version FAILED its dry run, or ran but its number was judged WRONG by the user. Fix it.\n"
                + "Previous code:\n(none)\nFailure:\nboom\n"));
        assertFalse(prompts.collectorPrompt("K", "d", "spec", List.of("file"), null, null).contains("FAILED its dry run"));
    }

    @Test
    void parseProposal_readsSources_andIgnoresLegacyFields() {
        CapabilityGenerator.Proposal p = prompts.parseProposal(new TextNode("{\"proposal\":\" x \",\"platform\":\"SERVER\","
                + "\"sources\":[\"ActivityWatch\",\"activitywatch\",\"file\"],\"measures\":\"m\"}"));
        assertEquals("x", p.proposal());
        assertEquals(List.of("activitywatch", "file"), p.sources());
        assertEquals("m", p.measures());
        assertNull(p.dataSource());
    }

    @Test
    void parseProposal_rejectsBadShapes() {
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(new TextNode("{\"sources\":[]}")));
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(new TextNode("no json here")));
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(null));
    }
}
