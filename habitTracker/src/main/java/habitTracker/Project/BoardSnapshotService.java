package habitTracker.Project;

import habitTracker.Project.BoardSnapshot.*;
import habitTracker.integrations.trello.TrelloApiClient;
import habitTracker.integrations.trello.TrelloGatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Fetches a board from Trello, maps raw JSON to BoardSnapshot, upserts it. No auth checks here:
// callers (ProjectService) have already resolved ownership.
@Service
@RequiredArgsConstructor
public class BoardSnapshotService {

    static final long TTL_SECONDS = 60;
    private static final String META_LIST = "_meta";

    private final BoardSnapshotRepository repository;
    private final TrelloApiClient trello;

    public record Result(BoardSnapshot snapshot, boolean stale) {}

    /** Cached snapshot if fresh (<60s) and !refresh; else Trello. On Trello failure falls back to a stale snapshot. */
    public Result get(Project p, boolean refresh) {
        BoardSnapshot cached = repository.findByProjectId(p.getId()).orElse(null);
        if (cached != null && !refresh && cached.getSyncedAt() != null
                && cached.getSyncedAt().plusSeconds(TTL_SECONDS).isAfter(Instant.now())) {
            return new Result(cached, false);
        }
        try {
            return new Result(store(p, trello.getBoard(p.getUserId(), p.getTrelloBoardId())), false);
        } catch (TrelloGatewayException e) {
            if (cached != null) return new Result(cached, true);
            throw e;
        }
    }

    /** Maps a raw Trello board (from TrelloApiClient.getBoard) and upserts it for the project. */
    public BoardSnapshot store(Project p, Map<String, Object> rawBoard) {
        BoardSnapshot s = repository.findByProjectId(p.getId()).orElseGet(BoardSnapshot::new);
        s.setProjectId(p.getId());
        s.setUserId(p.getUserId());
        s.setBoardId(p.getTrelloBoardId());
        s.setName(str(rawBoard.get("name")));
        s.setUrl(str(rawBoard.get("url")));
        s.setSyncedAt(Instant.now());
        map(rawBoard, s);
        return repository.save(s);
    }

    public void delete(String projectId) {
        repository.deleteByProjectId(projectId);
    }

    @SuppressWarnings("unchecked")
    private void map(Map<String, Object> raw, BoardSnapshot s) {
        Map<String, String> listNames = new HashMap<>();
        List<ListView> lists = new ArrayList<>();
        for (Object o : listOf(raw.get("lists"))) {
            Map<String, Object> l = (Map<String, Object>) o;
            String name = str(l.get("name"));
            if (META_LIST.equalsIgnoreCase(name)) continue; // hidden with its cards
            listNames.put(str(l.get("id")), name);
            lists.add(new ListView(str(l.get("id")), name, num(l.get("pos")), bool(l.get("closed"))));
        }
        List<CardView> cards = new ArrayList<>();
        for (Object o : listOf(raw.get("cards"))) {
            Map<String, Object> c = (Map<String, Object>) o;
            String idList = str(c.get("idList"));
            if (!listNames.containsKey(idList)) continue; // meta list or unknown list
            List<LabelView> labels = new ArrayList<>();
            for (Object lo : listOf(c.get("labels"))) {
                Map<String, Object> l = (Map<String, Object>) lo;
                labels.add(new LabelView(str(l.get("id")), str(l.get("name")), str(l.get("color"))));
            }
            BadgesView badges = null;
            if (c.get("badges") instanceof Map<?, ?> b) {
                badges = new BadgesView(intOf(b.get("checkItems")), intOf(b.get("checkItemsChecked")),
                        b.get("description") instanceof Boolean d ? d : null, intOf(b.get("comments")));
            }
            String listName = listNames.get(idList);
            boolean done = labels.stream().anyMatch(l -> "done".equalsIgnoreCase(l.getName()))
                    || "Done".equalsIgnoreCase(listName) || "Completed".equalsIgnoreCase(listName);
            cards.add(new CardView(str(c.get("id")), idList, str(c.get("name")), str(c.get("desc")), num(c.get("pos")),
                    str(c.get("due")), c.get("dueComplete") instanceof Boolean dc ? dc : null, bool(c.get("closed")),
                    labels, badges, str(c.get("url")), done));
        }
        s.setLists(lists);
        s.setCards(cards);
    }

    private static List<?> listOf(Object o) { return o instanceof List<?> l ? l : List.of(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static boolean bool(Object o) { return o instanceof Boolean b && b; }
    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : null; }
    private static Integer intOf(Object o) { return o instanceof Number n ? n.intValue() : null; }
}
