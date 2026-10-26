package habitTracker.Project;

import habitTracker.integrations.trello.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BoardSyncSchedulerTest {

    BoardSnapshotRepository snapRepo = mock(BoardSnapshotRepository.class);
    ProjectRepository projectRepo = mock(ProjectRepository.class);
    TrelloCredentialRepository creds = mock(TrelloCredentialRepository.class);
    TrelloApiClient trello = mock(TrelloApiClient.class);
    BoardSnapshotService svc = new BoardSnapshotService(snapRepo, trello);
    BoardSyncScheduler sched = new BoardSyncScheduler(snapRepo, projectRepo, creds, svc);

    @BeforeEach
    void setup() {
        sched.pauseMs = 0;
        when(snapRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(snapRepo.findByProjectId(any())).thenAnswer(i -> Optional.empty());
        when(creds.findByUserId(any())).thenReturn(Optional.of(new TrelloCredential()));
    }

    private BoardSnapshot snap(String project, String user, String board, String activity) {
        BoardSnapshot s = BoardSnapshot.builder().projectId(project).userId(user).boardId(board)
                .dateLastActivity(activity).syncedAt(Instant.now().minusSeconds(3600)).build();
        when(projectRepo.findById(project)).thenReturn(Optional.of(
                Project.builder().id(project).userId(user).trelloBoardId(board).build()));
        return s;
    }

    private static Map<String, Object> raw(String activity) {
        return Map.of("name", "B", "url", "u", "dateLastActivity", activity, "lists", List.of(), "cards", List.of());
    }

    @Test
    void fetchesOnlyChangedBoards() {
        BoardSnapshot same = snap("p1", "u1", "b1", "t1");
        BoardSnapshot changed = snap("p2", "u1", "b2", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(same, changed));
        when(trello.getBoardActivity("u1", "b1")).thenReturn("t1");
        when(trello.getBoardActivity("u1", "b2")).thenReturn("t2");
        when(trello.getBoard("u1", "b2")).thenReturn(raw("t2"));

        sched.syncAll();

        verify(trello, never()).getBoard("u1", "b1");
        verify(trello).getBoard("u1", "b2");
        assertTrue(same.getSyncedAt().isAfter(Instant.now().minusSeconds(60))); // bumped
        verify(snapRepo, times(2)).save(any());
    }

    @Test
    void failureOfOneBoardOrUserDoesNotAbortLoop() {
        BoardSnapshot bad = snap("p1", "u1", "b1", "t1");
        BoardSnapshot good = snap("p2", "u1", "b2", "t1");
        BoardSnapshot otherUser = snap("p3", "u2", "b3", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(bad, good, otherUser));
        when(trello.getBoardActivity("u1", "b1")).thenThrow(new TrelloGatewayException("boom", false, null, 500));
        when(trello.getBoardActivity("u1", "b2")).thenThrow(new IllegalStateException("weird"));
        when(trello.getBoardActivity("u2", "b3")).thenReturn("t9");
        when(trello.getBoard("u2", "b3")).thenReturn(raw("t9"));

        sched.syncAll();

        verify(trello).getBoard("u2", "b3");
    }

    @Test
    void unauthorizedSkipsRestOfThatUserOnly() {
        BoardSnapshot a1 = snap("p1", "u1", "b1", "t1");
        BoardSnapshot a2 = snap("p2", "u1", "b2", "t1");
        BoardSnapshot b1 = snap("p3", "u2", "b3", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(a1, a2, b1));
        when(trello.getBoardActivity("u1", "b1")).thenThrow(new TrelloAuthException());
        when(trello.getBoardActivity("u2", "b3")).thenReturn("t1");

        sched.syncAll();

        verify(trello, never()).getBoardActivity("u1", "b2");
        verify(trello).getBoardActivity("u2", "b3");
    }

    @Test
    void usersWithoutCredentialsAreSkipped() {
        BoardSnapshot s = snap("p1", "u1", "b1", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(s));
        when(creds.findByUserId("u1")).thenReturn(Optional.empty());

        sched.syncAll();

        verifyNoInteractions(trello);
    }

    @Test
    void notFoundMarksBoardMissingAndKeepsProject() {
        BoardSnapshot s = snap("p1", "u1", "b1", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(s));
        when(trello.getBoardActivity("u1", "b1")).thenThrow(new TrelloGatewayException("nf", false, null, 404));

        sched.syncAll();

        assertTrue(s.isBoardMissing());
        verify(projectRepo, never()).deleteById(any());
        verify(projectRepo, never()).delete(any());
    }

    @Test
    void rateLimitStopsTheRun() {
        BoardSnapshot a = snap("p1", "u1", "b1", "t1");
        BoardSnapshot b = snap("p2", "u2", "b2", "t1");
        when(snapRepo.findAll()).thenReturn(List.of(a, b));
        when(trello.getBoardActivity("u1", "b1")).thenThrow(new TrelloGatewayException("rl", false, null, 429, "5"));

        sched.syncAll();

        verify(trello, never()).getBoardActivity("u2", "b2");
    }

    @Test
    void getBoardProbesWhenStaleAndFetchesOnlyIfChanged() {
        BoardSnapshot s = snap("p1", "u1", "b1", "t1");
        Project p = Project.builder().id("p1").userId("u1").trelloBoardId("b1").build();
        when(snapRepo.findByProjectId("p1")).thenReturn(Optional.of(s));
        when(trello.getBoardActivity("u1", "b1")).thenReturn("t1");

        var r = svc.get(p, false);
        assertFalse(r.stale());
        verify(trello, never()).getBoard(any(), any());
        assertTrue(s.getSyncedAt().isAfter(Instant.now().minusSeconds(60)));

        s.setSyncedAt(Instant.now().minusSeconds(3600));
        when(trello.getBoardActivity("u1", "b1")).thenReturn("t2");
        when(trello.getBoard("u1", "b1")).thenReturn(raw("t2"));
        svc.get(p, false);
        verify(trello, times(1)).getBoard("u1", "b1");
        assertEquals("t2", s.getDateLastActivity());

        // refresh=true never probes
        clearInvocations(trello);
        when(trello.getBoard("u1", "b1")).thenReturn(raw("t2"));
        svc.get(p, true);
        verify(trello, never()).getBoardActivity(any(), any());
        verify(trello).getBoard("u1", "b1");
    }

    @Test
    void getBoardReportsMissingOn404() {
        BoardSnapshot s = snap("p1", "u1", "b1", "t1");
        Project p = Project.builder().id("p1").userId("u1").trelloBoardId("b1").build();
        when(snapRepo.findByProjectId("p1")).thenReturn(Optional.of(s));
        when(trello.getBoardActivity("u1", "b1")).thenThrow(new TrelloGatewayException("nf", false, null, 404));

        var r = svc.get(p, false);

        assertFalse(r.stale());
        assertTrue(r.snapshot().isBoardMissing());
    }
}
