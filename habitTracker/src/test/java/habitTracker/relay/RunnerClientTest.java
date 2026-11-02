package habitTracker.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunnerClientTest {

    DeviceService devices;
    UplinkRegistry uplinks;
    RunnerClient client;
    RunnerJobService jobs;

    @BeforeEach
    void setup() {
        devices = mock(DeviceService.class);
        uplinks = mock(UplinkRegistry.class);
        client = new RunnerClient(devices, uplinks, 200);
        jobs = new RunnerJobService(client, new ObjectMapper());
        when(devices.owned("u1", "d1")).thenReturn(Optional.of(new Device()));
    }

    private void answer(int status, String body) {
        when(uplinks.request(eq("d1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new UplinkRegistry.Request("r1", CompletableFuture.completedFuture(
                        new UplinkRegistry.Response(status, "application/json", body)))));
    }

    @Test
    void otherUsersDeviceIsNotFoundAndNothingSent() {
        RunnerUnavailableException e = assertThrows(RunnerUnavailableException.class,
                () -> client.call("u2", "d1", "GET", "/v1/jobs", ""));
        assertEquals(RunnerUnavailableException.Reason.DEVICE_NOT_FOUND, e.getReason());
        verifyNoInteractions(uplinks);
    }

    @Test
    void noUplinkIsOffline() {
        when(uplinks.request(any(), any(), any(), any())).thenReturn(Optional.empty());
        RunnerUnavailableException e = assertThrows(RunnerUnavailableException.class,
                () -> client.call("u1", "d1", "GET", "/v1/jobs", ""));
        assertEquals("runner_offline", e.code());
    }

    @Test
    void closedUplinkIsOffline() {
        when(uplinks.request(any(), any(), any(), any())).thenReturn(Optional.of(
                new UplinkRegistry.Request("r1", CompletableFuture.failedFuture(new IOException("uplink closed")))));
        assertEquals(RunnerUnavailableException.Reason.OFFLINE, assertThrows(RunnerUnavailableException.class,
                () -> client.call("u1", "d1", "GET", "/v1/x", "")).getReason());
    }

    @Test
    void timeoutCancelsPending() {
        when(uplinks.request(any(), any(), any(), any())).thenReturn(Optional.of(
                new UplinkRegistry.Request("r9", new CompletableFuture<>())));
        assertEquals(RunnerUnavailableException.Reason.TIMEOUT, assertThrows(RunnerUnavailableException.class,
                () -> client.call("u1", "d1", "GET", "/v1/x", "")).getReason());
        verify(uplinks).cancel("r9");
    }

    @Test
    void startJobSendsSpecAndReturnsId() {
        answer(202, "{\"jobId\":\"j1\"}");
        assertEquals("j1", jobs.startJob("u1", "d1",
                new RunnerJobService.JobSpec("map", "p1", "do it", null, 50)));
        verify(uplinks).request(eq("d1"), eq("POST"), eq("/v1/jobs"),
                argThat(b -> b.contains("\"kind\":\"map\"") && b.contains("\"projectId\":\"p1\"")
                        && b.contains("\"maxTurns\":50") && !b.contains("model")));
    }

    @Test
    void pollAndCancelPaths() {
        answer(200, "{\"state\":\"running\"}");
        assertEquals("running", jobs.pollJob("u1", "d1", "j1", 7).path("state").asText());
        verify(uplinks).request("d1", "GET", "/v1/jobs/j1?after=7", "");
        jobs.cancelJob("u1", "d1", "j1");
        verify(uplinks).request("d1", "DELETE", "/v1/jobs/j1", "");
    }

    @Test
    void non2xxIsCallExceptionAndBadJobIdRejected() {
        answer(404, "{\"error\":\"no such job\"}");
        RunnerCallException e = assertThrows(RunnerCallException.class, () -> jobs.pollJob("u1", "d1", "j1", 0));
        assertEquals(404, e.getStatus());
        assertThrows(IllegalArgumentException.class, () -> jobs.pollJob("u1", "d1", "../projects", 0));
    }
}
