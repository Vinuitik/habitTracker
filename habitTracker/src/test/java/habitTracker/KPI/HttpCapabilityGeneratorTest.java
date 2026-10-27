package habitTracker.KPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpCapabilityGeneratorTest {

    RestTemplate rt = new RestTemplate();
    MockRestServiceServer server;
    HttpCapabilityGenerator gen;

    @BeforeEach
    void setup() {
        server = MockRestServiceServer.createServer(rt);
        gen = new HttpCapabilityGenerator("http://mb:8092/", rt);
    }

    @Test
    void proposeSendsModeAndParsesDeclared() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.mode").value("propose"))
                .andExpect(jsonPath("$.kpiName").value("K"))
                .andExpect(jsonPath("$.description").value("d"))
                .andRespond(withSuccess("{\"proposal\":\"spec\",\"platform\":\"SERVER\","
                        + "\"declaredCapabilities\":{\"network\":[\"a.com\"],\"secrets\":[]}}", MediaType.APPLICATION_JSON));
        CapabilityGenerator.Proposal p = gen.propose("K", "d", List.of(), List.of());
        assertEquals("spec", p.options().get(0).summary());
        assertEquals(Map.of("network", List.of("a.com"), "secrets", List.of()), p.options().get(0).declaredCapabilities());
        server.verify();
    }

    @Test
    void proposeSendsConversation_andParsesLabelledOptions() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability"))
                .andExpect(jsonPath("$.conversation[0].role").value("user"))
                .andExpect(jsonPath("$.conversation[0].text").value("only free"))
                .andExpect(jsonPath("$.previousOptions[0].title").value("Old"))
                .andRespond(withSuccess("{\"reply\":\"ok\",\"options\":[{\"title\":\"T\",\"summary\":\"S\","
                        + "\"cost\":\"free_tier\",\"method\":\"OFFICIAL_API\",\"sources\":[\"https://x.com\"]},"
                        + "{\"title\":\"no summary, dropped\"}]}", MediaType.APPLICATION_JSON));
        CapabilityGenerator.Proposal p = gen.propose("K", "d", List.of(new CapabilityTurn("user", "only free")),
                List.of(CapabilityOption.of("old", "SERVER", Map.of()).vetted(Map.of(), null)) .stream()
                        .map(o -> new CapabilityOption("Old", o.summary(), o.platform(), o.declaredCapabilities(), o.cost(),
                                null, null, o.method(), o.difficulty(), null, List.of(), null)).toList());
        assertEquals("ok", p.reply());
        assertEquals(1, p.options().size());
        CapabilityOption o = p.options().get(0);
        assertEquals("FREE_TIER", o.cost());
        assertEquals("UNKNOWN", o.difficulty());
        assertEquals("SERVER", o.platform());
        assertEquals(List.of("https://x.com"), o.sources());
        assertEquals(Map.of("network", List.of(), "secrets", List.of()), o.declaredCapabilities());
        server.verify();
    }

    @Test
    void generateSendsSeedAndReturnsSource() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability"))
                .andExpect(jsonPath("$.mode").value("generate"))
                .andExpect(jsonPath("$.proposal").value("spec"))
                .andExpect(jsonPath("$.previousCode").value("old"))
                .andExpect(jsonPath("$.previousError").value("boom"))
                .andRespond(withSuccess("{\"sourceCode\":\"print(1)\"}", MediaType.APPLICATION_JSON));
        assertEquals("print(1)", gen.generate("K", "d", "spec", Map.of(), "old", "boom"));
        server.verify();
    }

    @Test
    void conflictIsBusy() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability")).andRespond(withStatus(HttpStatus.CONFLICT));
        CapabilityGeneratorException e = assertThrows(CapabilityGeneratorException.class, () -> gen.propose("K", "d", List.of(), List.of()));
        assertTrue(e.isBusy());
    }

    @Test
    void otherErrorsAreNotBusy_andMissingFieldsFail() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability")).andRespond(withServerError());
        assertFalse(assertThrows(CapabilityGeneratorException.class, () -> gen.propose("K", "d", List.of(), List.of())).isBusy());

        server.reset();
        server.expect(requestTo("http://mb:8092/internal/agent/capability"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(CapabilityGeneratorException.class, () -> gen.generate("K", "d", "s", Map.of(), null, null));
    }
}
