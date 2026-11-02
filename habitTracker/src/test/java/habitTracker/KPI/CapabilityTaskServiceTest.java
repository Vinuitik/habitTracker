package habitTracker.KPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityTaskServiceTest {

    private final CapabilityAgentService agent = mock(CapabilityAgentService.class);
    private final CapabilityTaskService tasks = new CapabilityTaskService(agent);

    @AfterEach
    void stop() {
        tasks.shutdown();
    }

    private static CapabilityDTO dto(CapabilityStatus status) {
        return new CapabilityDTO("c1", "k1", 1, status, "d", "p", "Open PRs", "GitHub API", CapabilityPlatform.SERVER,
                Map.of("network", List.of("api.github.com"), "secrets", List.of()), null, 0, 3, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitDone(String user, String kpi, String jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> s = tasks.poll(user, kpi, jobId, 0).orElseThrow();
            if (!"running".equals(s.get("state"))) return s;
            Thread.sleep(10);
        }
        fail("task did not finish");
        return null;
    }

    @Test
    @SuppressWarnings("unchecked")
    void propose_streamsStepsAndReturnsParsedResultWithUsage() throws Exception {
        when(agent.proposeAs("u", "K", "count PRs")).thenAnswer(inv -> {
            CapabilityProgress.step("Drafting a plan on your runner");
            CapabilityProgress.runnerEvent("step", "Reading the request");
            CapabilityProgress.usage(new ObjectMapper().readTree("{\"input_tokens\":100,\"output_tokens\":23}"));
            return dto(CapabilityStatus.PROPOSED);
        });
        String id = tasks.start("u", "K", "propose", null, "count PRs");
        Map<String, Object> s = waitDone("u", "K", id);

        assertEquals("done", s.get("state"));
        assertEquals(CapabilityStatus.PROPOSED, ((CapabilityDTO) s.get("result")).status());
        List<CapabilityTaskService.Event> ev = (List<CapabilityTaskService.Event>) s.get("events");
        assertEquals(List.of("Drafting a plan on your runner", "Reading the request"),
                ev.stream().map(CapabilityTaskService.Event::text).toList());
        assertEquals(123L, ((Map<String, Object>) s.get("usage")).get("totalTokens"));
        assertEquals(2L, s.get("nextSeq"));
        assertTrue(((List<?>) tasks.poll("u", "K", id, 2).orElseThrow().get("events")).isEmpty());
    }

    @Test
    void noRunner_failsWithActionableSentenceAndCode() throws Exception {
        when(agent.approveAs(any(), any(), any())).thenThrow(CapabilityGeneratorException.noRunner(null));
        String id = tasks.start("u", "K", "approve", "c1", null);
        Map<String, Object> s = waitDone("u", "K", id);
        assertEquals("failed", s.get("state"));
        assertEquals("Connect a runner to generate this capability", s.get("error"));
        assertEquals("runner_offline", s.get("code"));
        assertFalse(s.containsKey("result"));
    }

    @Test
    void poll_isScopedToUserAndKpi() throws Exception {
        when(agent.repairAs("u", "K")).thenReturn(dto(CapabilityStatus.ACTIVE));
        String id = tasks.start("u", "K", "repair", null, null);
        waitDone("u", "K", id);
        assertTrue(tasks.poll("other", "K", id, 0).isEmpty());
        assertTrue(tasks.poll("u", "Other", id, 0).isEmpty());
    }

    @Test
    void secondStartWhileRunning_conflicts() throws Exception {
        Object gate = new Object();
        when(agent.proposeAs(any(), any(), any())).thenAnswer(inv -> {
            synchronized (gate) { gate.wait(2000); }
            return dto(CapabilityStatus.PROPOSED);
        });
        String id = tasks.start("u", "K", "propose", null, "x");
        assertEquals(id, tasks.active("u", "K").orElseThrow());
        assertThrows(IllegalStateException.class, () -> tasks.start("u", "K", "propose", null, "x"));
        synchronized (gate) { gate.notifyAll(); }
        waitDone("u", "K", id);
        assertTrue(tasks.active("u", "K").isEmpty());
    }

    @Test
    void badInput_rejectedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> tasks.start("u", "K", "explode", null, null));
        assertThrows(IllegalArgumentException.class, () -> tasks.start("u", "K", "approve", null, null));
    }
}
