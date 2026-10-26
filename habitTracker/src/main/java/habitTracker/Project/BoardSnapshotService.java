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

    public enum Outcome { UNCHANGED, UPDATED, MISSING }

    public record Synced(BoardSnapshot snapshot, Outcome outcome) {}

    /** Cached snapshot if fresh (<60s) and !refresh; stale cache is probed (dateLastActivity) and fully refetched only if changed. On Trello failure falls back to a stale snapshot. */
    public Result get(Project p, boolean refresh) {
        BoardSnapshot cached = repository.findByProjectId(p.getId()).orElse(null);
        if (cached != null && !refresh && cached.getSyncedAt() != null
                && cached.getSyncedAt().plusSeconds(TTL_SECONDS).isAfter(Instant.now())) {
            return new Result(cached, false);
        }
        try {
            if (cached != null && !refresh) return new Result(syncIfChanged(p, cached).snapshot(), false);
            return new Result(store(p, trello.getBoard(p.getUserId(), p.getTrelloBoardId())), false);
        } catch (TrelloGatewayException e) {
            if (cached != null) {
                if (e.getStatus() == 404) return new Result(markMissing(cached), false);
                return new Result(cached, true);
            }
            throw e;
        }
    }

    /**
     * Probes dateLastActivity; equal to the snapshot's → bump syncedAt only (UNCHANGED), else full fetch + upsert (UPDATED).
     * Trello 404 → boardMissing=true (MISSING). Other Trello errors propagate. Shared by GET /board and BoardSyncScheduler.
     */
    public Synced syncIfChanged(Project p, BoardSnapshot cached) {
        try {
            String activity = cached.getDateLastActivity() == null ? null
                    : trello.getBoardActivity(p.getUserId(), p.getTrelloBoardId());
            if (activity != null && activity.equals(cached.getDateLastActivity())) {
                cached.setSyncedAt(Instant.now());
                cached.setBoardMissing(false);
                return new Synced(repository.save(cached), Outcome.UNCHANGED);
            }
            return new Synced(store(p, trello.getBoard(p.getUserId(), p.getTrelloBoardId())), Outcome.UPDATED);
        } catch (TrelloGatewayException e) {
            if (e.getStatus() == 404) return new Synced(markMissing(cached), Outcome.MISSING);
            throw e;
        }
    }

    private BoardSnapshot markMissing(BoardSnapshot s) {
        s.setBoardMissing(true);
        s.setSyncedAt(Instant.now());
        return repository.save(s);
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
        s.setDateLastActivity(str(rawBoard.get("dateLastActivity")));
        s.setBoardMissing(false);
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
            cards.add(mapCard(c, idList, listNames.get(idList)));
        }
        s.setLists(lists);
        s.setCards(cards);
    }

    @SuppressWarnings("unchecked")
    private static CardView mapCard(Map<String, Object> c, String idList, String listName) {
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
        CardView v = new CardView(str(c.get("id")), idList, str(c.get("name")), str(c.get("desc")), num(c.get("pos")),
                str(c.get("due")), c.get("dueComplete") instanceof Boolean dc ? dc : null, bool(c.get("closed")),
                labels, badges, str(c.get("url")), false);
        v.setDone(isDone(v, listName));
        return v;
    }

    private static boolean isDone(CardView c, String listName) {
        return c.getLabels().stream().anyMatch(l -> "done".equalsIgnoreCase(l.getName()))
                || "Done".equalsIgnoreCase(listName) || "Completed".equalsIgnoreCase(listName);
    }

    // ---- write-through patching (no Trello call; no-op when the project has no snapshot yet) ----

    /** Cached snapshot or null; never calls Trello. */
    public BoardSnapshot cached(Project p) {
        return repository.findByProjectId(p.getId()).orElse(null);
    }

    /** Replaces/appends the list from a Trello list response, recomputes done for its cards. syncedAt unchanged. */
    public ListView patchList(Project p, Map<String, Object> rawList) {
        String id = str(rawList.get("id"));
        ListView v = new ListView(id, str(rawList.get("name")), num(rawList.get("pos")), bool(rawList.get("closed")));
        BoardSnapshot s = cached(p);
        if (s == null) return v;
        replaceOrAdd(s.getLists(), v, ListView::getId);
        Map<String, String> names = new HashMap<>();
        s.getLists().forEach(l -> names.put(l.getId(), l.getName()));
        for (CardView c : s.getCards()) {
            if (id.equals(c.getIdList())) c.setDone(isDone(c, names.get(id)));
        }
        repository.save(s);
        return v;
    }

    /** Replaces/appends the card from a Trello card response (same mapper as board sync). Card in an unknown/_meta list is dropped. */
    public CardView patchCard(Project p, Map<String, Object> rawCard) {
        BoardSnapshot s = cached(p);
        String idList = str(rawCard.get("idList"));
        String listName = s == null ? null : s.getLists().stream()
                .filter(l -> l.getId().equals(idList)).map(ListView::getName).findFirst().orElse(null);
        CardView v = mapCard(rawCard, idList, listName);
        if (s == null) return v;
        if (listName == null) s.getCards().removeIf(c -> c.getId().equals(v.getId()));
        else replaceOrAdd(s.getCards(), v, CardView::getId);
        repository.save(s);
        return v;
    }

    private static <T> void replaceOrAdd(List<T> list, T item, java.util.function.Function<T, String> id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.apply(list.get(i)).equals(id.apply(item))) { list.set(i, item); return; }
        }
        list.add(item);
    }

    private static List<?> listOf(Object o) { return o instanceof List<?> l ? l : List.of(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static boolean bool(Object o) { return o instanceof Boolean b && b; }
    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : null; }
    private static Integer intOf(Object o) { return o instanceof Number n ? n.intValue() : null; }
}
