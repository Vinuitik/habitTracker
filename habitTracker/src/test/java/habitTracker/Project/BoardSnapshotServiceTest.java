package habitTracker.Project;

import habitTracker.integrations.trello.TrelloApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BoardSnapshotServiceTest {

    BoardSnapshotRepository repo = mock(BoardSnapshotRepository.class);
    BoardSnapshotService svc = new BoardSnapshotService(repo, mock(TrelloApiClient.class));

    @Test
    void computesDoneAndHidesMeta() {
        when(repo.findByProjectId("p")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        Map<String, Object> raw = Map.of("name", "B", "url", "u",
                "lists", List.of(
                        Map.of("id", "t", "name", "Todo", "pos", 1, "closed", false),
                        Map.of("id", "d", "name", "done", "pos", 2, "closed", false),
                        Map.of("id", "c", "name", "Completed", "pos", 3, "closed", false),
                        Map.of("id", "m", "name", "_meta", "pos", 4, "closed", false)),
                "cards", List.of(
                        Map.of("id", "1", "idList", "t", "name", "plain", "labels", List.of()),
                        Map.of("id", "2", "idList", "t", "name", "labeled", "labels", List.of(Map.of("id", "x", "name", "DONE", "color", "green"))),
                        Map.of("id", "3", "idList", "d", "name", "in done list", "labels", List.of()),
                        Map.of("id", "4", "idList", "c", "name", "in completed", "labels", List.of()),
                        Map.of("id", "5", "idList", "m", "name", "meta card", "labels", List.of()),
                        Map.of("id", "6", "idList", "t", "name", "badged", "labels", List.of(),
                                "badges", Map.of("checkItems", 3, "checkItemsChecked", 1, "description", true, "comments", 2))));
        BoardSnapshot s = svc.store(Project.builder().id("p").userId("u").trelloBoardId("b").build(), raw);

        assertEquals(3, s.getLists().size());
        assertTrue(s.getLists().stream().noneMatch(l -> l.getName().equals("_meta")));
        assertEquals(5, s.getCards().size());
        assertTrue(s.getCards().stream().noneMatch(c -> c.getId().equals("5")));
        var done = s.getCards().stream().filter(BoardSnapshot.CardView::isDone).map(BoardSnapshot.CardView::getId).toList();
        assertEquals(List.of("2", "3", "4"), done);
        var badges = s.getCards().stream().filter(c -> c.getId().equals("6")).findFirst().get().getBadges();
        assertEquals(3, badges.getCheckItems());
        assertEquals(2, badges.getComments());
        assertNotNull(s.getSyncedAt());
        assertEquals("b", s.getBoardId());
        assertNull(s.getLastActionId());
    }
}
