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
        String p = prompts.proposePrompt("K", "steal {{KPI_NAME}} $1 \\", List.of());
        assertTrue(p.contains("User wants this captured automatically: steal {{KPI_NAME}} $1 \\\n"));
        assertTrue(p.endsWith("\"credentials\": [{\"name\": \"<credential name>\", \"host\": \"<api hostname>\"}]}"));
    }

    @Test
    void proposePrompt_offersNoPlatformChoice() {
        String p = prompts.proposePrompt("K", "d", List.of());
        assertFalse(p.contains("SERVER"));
        assertFalse(p.contains("\"platform\""));
        assertFalse(p.contains("secrets"));
        assertTrue(p.contains("NO network of its own"));
        assertTrue(p.contains("(none yet)"));
    }

    @Test
    void collectorPromptWithFailure_seedsPreviousBundle() {
        String p = prompts.collectorPrompt("K", "d", "spec", List.of("activitywatch"), List.of(), null, "boom");
        assertTrue(p.contains("A previous version FAILED its dry run, or ran but its number was judged WRONG by the user. Fix it.\n"
                + "Previous code:\n(none)\nFailure:\nboom\n"));
        assertFalse(prompts.collectorPrompt("K", "d", "spec", List.of("file"), List.of(), null, null).contains("FAILED its dry run"));
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
    void credentials_reachBothPrompts_namesAndHostsOnly() {
        List<CredentialRef> refs = List.of(new CredentialRef("github", "api.github.com"), new CredentialRef("Bad Name", "x"));
        String p = prompts.proposePrompt("K", "d", refs);
        assertTrue(p.contains("- github (host api.github.com)"), p);
        assertFalse(p.contains("Bad Name"));
        String c = prompts.collectorPrompt("K", "d", "spec", List.of("http"), List.of(new CredentialRef("github", "api.github.com")), null, null);
        assertTrue(c.contains("- github (host api.github.com)"), c);
        assertTrue(c.contains("/in/http/"), c);
        assertTrue(prompts.collectorPrompt("K", "d", "spec", List.of("file"), List.of(), null, null)
                .contains("may not use an http source"));
    }

    @Test
    void parseProposal_readsCredentials() {
        CapabilityGenerator.Proposal p = prompts.parseProposal(new TextNode("{\"proposal\":\"x\",\"sources\":[\"http\"],"
                + "\"credentials\":[{\"name\":\"GitHub\",\"host\":\"API.github.com\"},{\"name\":\"github\",\"host\":\"dup\"},\"junk\"]}"));
        assertEquals(List.of(new CredentialRef("github", "api.github.com")), p.credentials());
        assertEquals(List.of(), prompts.parseProposal(new TextNode("{\"proposal\":\"x\",\"sources\":[\"file\"]}")).credentials());
    }

    @Test
    void parseProposal_rejectsBadShapes() {
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(new TextNode("{\"sources\":[]}")));
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(new TextNode("no json here")));
        assertThrows(CapabilityGeneratorException.class, () -> prompts.parseProposal(null));
    }
}
