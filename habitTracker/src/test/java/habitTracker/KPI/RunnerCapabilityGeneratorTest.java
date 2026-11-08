package habitTracker.KPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.aiusage.AiUsage;
import habitTracker.aiusage.AiUsageService;
import habitTracker.relay.OnlineRunnerSelector;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.RunnerJobService;
import habitTracker.relay.RunnerUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunnerCapabilityGeneratorTest {

    ObjectMapper json = new ObjectMapper();
    RunnerJobService jobs = mock(RunnerJobService.class);
    OnlineRunnerSelector selector = mock(OnlineRunnerSelector.class);
    CapabilityPrompts prompts = new CapabilityPrompts(json);
    AiUsageService usage = mock(AiUsageService.class);
    RunnerCapabilityGenerator gen = new RunnerCapabilityGenerator(jobs, selector, prompts, usage, 10_000, 1, ms -> {});

    private JsonNode snap(String s) throws Exception {
        return json.readTree(s);
    }

    @Test
    void propose_runsKpiJobOnSelectedRunner_andParsesResult() throws Exception {
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(eq("u"), eq("dev1"), any())).thenReturn("j1");
        when(jobs.pollJob("u", "dev1", "j1", 0)).thenReturn(snap("{\"state\":\"running\",\"nextSeq\":3}"));
        when(jobs.pollJob("u", "dev1", "j1", 3)).thenReturn(snap("{\"state\":\"done\",\"nextSeq\":4,\"result\":"
                + "\"```json\\n{\\\"proposal\\\":\\\" Count PRs \\\","
                + "\\\"sources\\\":[\\\"file\\\"]}\\n```\"}"));

        CapabilityGenerator.Proposal p = gen.propose("u", "PRs", "count my PRs");

        assertEquals("Count PRs", p.proposal());
        assertEquals(List.of("file"), p.sources());
        ArgumentCaptor<RunnerJobService.JobSpec> spec = ArgumentCaptor.forClass(RunnerJobService.JobSpec.class);
        verify(jobs).startJob(eq("u"), eq("dev1"), spec.capture());
        assertEquals("kpi", spec.getValue().kind());
        assertNull(spec.getValue().projectId());
        assertEquals(3, spec.getValue().maxTurns());
        assertTrue(spec.getValue().prompt().contains("KPI name: PRs\nUser wants this captured automatically: count my PRs"));
        verify(selector).markUsed("dev1");
        verify(usage, atLeastOnce()).record(eq("u"), eq("j1"), any(), eq(AiUsageService.Source.kpi(AiUsage.KPI_PROPOSE, "PRs")));
    }

    @Test
    void noRunnerOnline_throwsNoRunnerWithoutStartingJob() {
        when(selector.pick("u")).thenReturn(Optional.empty());
        CapabilityGeneratorException e = assertThrows(CapabilityGeneratorException.class, () -> gen.propose("u", "K", "d"));
        assertTrue(e.isNoRunner());
        assertEquals("Connect a runner to generate this capability", e.getMessage());
        verifyNoInteractions(jobs);
    }

    @Test
    void runnerDropsMidPoll_isNoRunner() {
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(any(), any(), any())).thenReturn("j1");
        when(jobs.pollJob(any(), any(), any(), anyLong()))
                .thenThrow(new RunnerUnavailableException(RunnerUnavailableException.Reason.OFFLINE, "device offline"));
        assertTrue(assertThrows(CapabilityGeneratorException.class, () -> gen.propose("u", "K", "d")).isNoRunner());
    }

    @Test
    void failedJob_surfacesRunnerError() throws Exception {
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(any(), any(), any())).thenReturn("j1");
        when(jobs.pollJob(any(), any(), any(), anyLong())).thenReturn(snap("{\"state\":\"failed\",\"error\":\"auth expired\"}"));
        CapabilityGeneratorException e = assertThrows(CapabilityGeneratorException.class, () -> gen.propose("u", "K", "d"));
        assertFalse(e.isNoRunner());
        assertTrue(e.getMessage().contains("auth expired"));
    }

    @Test
    void busyRunner_isBusy() {
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(any(), any(), any())).thenThrow(new RunnerCallException(409, "{\"error\":\"busy\"}"));
        assertTrue(assertThrows(CapabilityGeneratorException.class, () -> gen.propose("u", "K", "d")).isBusy());
    }

    @Test
    void timeout_cancelsJob() throws Exception {
        RunnerCapabilityGenerator slow = new RunnerCapabilityGenerator(jobs, selector, prompts, usage, 0, 1, ms -> {});
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(any(), any(), any())).thenReturn("j1");
        when(jobs.pollJob(any(), any(), any(), anyLong())).thenReturn(snap("{\"state\":\"running\"}"));
        CapabilityGeneratorException e = assertThrows(CapabilityGeneratorException.class, () -> slow.propose("u", "K", "d"));
        assertTrue(e.getMessage().contains("timed out"));
        verify(jobs).cancelJob("u", "dev1", "j1");
    }

    @Test
    void unparseableResult_isGeneratorError() throws Exception {
        when(selector.pick("u")).thenReturn(Optional.of("dev1"));
        when(jobs.startJob(any(), any(), any())).thenReturn("j1");
        when(jobs.pollJob(any(), any(), any(), anyLong())).thenReturn(snap("{\"state\":\"done\",\"result\":\"sorry, no\"}"));
        assertThrows(CapabilityGeneratorException.class, () -> gen.propose("u", "K", "d"));
    }
}
