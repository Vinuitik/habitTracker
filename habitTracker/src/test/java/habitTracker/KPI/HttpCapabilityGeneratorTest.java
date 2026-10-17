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
        CapabilityGenerator.Proposal p = gen.propose("K", "d");
        assertEquals("spec", p.proposal());
        assertEquals(Map.of("network", List.of("a.com"), "secrets", List.of()), p.declaredCapabilities());
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
        CapabilityGeneratorException e = assertThrows(CapabilityGeneratorException.class, () -> gen.propose("K", "d"));
        assertTrue(e.isBusy());
    }

    @Test
    void otherErrorsAreNotBusy_andMissingFieldsFail() {
        server.expect(requestTo("http://mb:8092/internal/agent/capability")).andRespond(withServerError());
        assertFalse(assertThrows(CapabilityGeneratorException.class, () -> gen.propose("K", "d")).isBusy());

        server.reset();
        server.expect(requestTo("http://mb:8092/internal/agent/capability"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(CapabilityGeneratorException.class, () -> gen.generate("K", "d", "s", Map.of(), null, null));
    }
}
