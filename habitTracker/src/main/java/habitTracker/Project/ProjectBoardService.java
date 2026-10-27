package habitTracker.Project;

import habitTracker.Project.BoardSnapshot.CardView;
import habitTracker.Project.BoardSnapshot.LabelView;
import habitTracker.Project.BoardSnapshot.ListView;
import habitTracker.auth.SecurityUtils;
import habitTracker.integrations.trello.TrelloApiClient;
import habitTracker.integrations.trello.TrelloGatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// Write-through editing: Trello is the source of truth. Each method = ownership check -> board-membership
// check -> Trello write -> BoardSnapshotService patch of the cached snapshot (no refetch needed afterwards).
@Service
@RequiredArgsConstructor
public class ProjectBoardService {

    static final String DONE_LABEL = "done";

    private final ProjectRepository repository;
    private final TrelloApiClient trello;
    private final BoardSnapshotService snapshots;

    public static class NotFound extends ProjectService.NotFoundException {
        public NotFound(String what) { super(what + " not found"); }
    }

    public ListView createList(String projectId, Map<String, Object> body) {
        Project p = owned(projectId);
        String name = requireName(body);
        String pos = body.containsKey("pos") && body.get("pos") != null ? pos(body.get("pos")) : null;
        return snapshots.patchList(p, trello.createList(p.getUserId(), p.getTrelloBoardId(), name, pos));
    }

    public ListView updateList(String projectId, String listId, Map<String, Object> body) {
        Project p = owned(projectId);
        ensureList(p, listId);
        Map<String, String> params = new LinkedHashMap<>();
        if (body.containsKey("name")) params.put("name", requireName(body));
        if (body.containsKey("pos")) params.put("pos", pos(body.get("pos")));
        if (body.containsKey("closed")) params.put("closed", bool(body.get("closed")));
        if (params.isEmpty()) throw new IllegalArgumentException("no fields to update");
        return snapshots.patchList(p, trello(() -> trello.updateList(p.getUserId(), listId, params), "List"));
    }

    public CardView createCard(String projectId, Map<String, Object> body) {
        Project p = owned(projectId);
        String name = requireName(body);
        String idList = str(body.get("idList"));
        if (idList == null || idList.isBlank()) throw new IllegalArgumentException("idList is required");
        ensureList(p, idList);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("idList", idList);
        params.put("name", name);
        if (body.get("desc") != null) params.put("desc", str(body.get("desc")));
        params.put("pos", body.get("pos") != null ? pos(body.get("pos")) : "bottom");
        return snapshots.patchCard(p, trello(() -> trello.createCard(p.getUserId(), params), "List"));
    }

    /** Only provided fields are sent; an explicit JSON null for due sends due=null (Trello clears it). */
    public CardView updateCard(String projectId, String cardId, Map<String, Object> body) {
        Project p = owned(projectId);
        Map<String, String> params = new LinkedHashMap<>();
        if (body.containsKey("name")) params.put("name", requireName(body));
        if (body.containsKey("desc")) params.put("desc", body.get("desc") == null ? "" : str(body.get("desc")));
        if (body.containsKey("due")) params.put("due", body.get("due") == null ? "null" : str(body.get("due")));
        if (body.containsKey("dueComplete")) params.put("dueComplete", bool(body.get("dueComplete")));
        if (body.containsKey("idList")) {
            String idList = str(body.get("idList"));
            if (idList == null || idList.isBlank()) throw new IllegalArgumentException("idList must not be blank");
            ensureList(p, idList);
            params.put("idList", idList);
        }
        if (body.containsKey("pos")) params.put("pos", pos(body.get("pos")));
        if (body.containsKey("closed")) params.put("closed", bool(body.get("closed")));
        if (params.isEmpty()) throw new IllegalArgumentException("no fields to update");
        ensureCard(p, cardId);
        return snapshots.patchCard(p, trello(() -> trello.updateCard(p.getUserId(), cardId, params), "Card"));
    }

    /** "done" label (created green if missing) + move to an open Completed/Done list if one exists (never created). */
    public CardView complete(String projectId, String cardId) {
        Project p = owned(projectId);
        String u = p.getUserId();
        Map<String, Object> card = trello(() -> trello.getCard(u, cardId), "Card");
        checkBoard(p, card, "Card");
        if (!hasDoneLabel(card)) {
            String labelId = trello.getBoardLabels(u, p.getTrelloBoardId()).stream()
                    .filter(l -> DONE_LABEL.equalsIgnoreCase(str(l.get("name")))).map(l -> str(l.get("id"))).findFirst()
                    .orElseGet(() -> str(trello.createLabel(u, p.getTrelloBoardId(), DONE_LABEL, "green").get("id")));
            trello(() -> { trello.addCardLabel(u, cardId, labelId); return null; }, "Card");
        }
        String target = completedList(p);
        if (target != null && !target.equals(str(card.get("idList")))) {
            Map<String, String> move = new LinkedHashMap<>();
            move.put("idList", target);
            move.put("pos", "top");
            trello(() -> trello.updateCard(u, cardId, move), "Card");
        }
        return snapshots.patchCard(p, trello(() -> trello.getCard(u, cardId), "Card"));
    }

    /** Removes the "done" label only; the card is not moved. */
    public CardView reopen(String projectId, String cardId) {
        Project p = owned(projectId);
        String u = p.getUserId();
        Map<String, Object> card = trello(() -> trello.getCard(u, cardId), "Card");
        checkBoard(p, card, "Card");
        for (Object o : card.get("labels") instanceof List<?> l ? l : List.of()) {
            if (o instanceof Map<?, ?> m && DONE_LABEL.equalsIgnoreCase(str(m.get("name")))) {
                String labelId = str(m.get("id"));
                trello(() -> { trello.removeCardLabel(u, cardId, labelId); return null; }, "Card");
            }
        }
        return snapshots.patchCard(p, trello(() -> trello.getCard(u, cardId), "Card"));
    }

    // ---- integrity: every id must belong to the project's board ----

    private void ensureList(Project p, String listId) {
        BoardSnapshot s = snapshots.cached(p);
        if (s != null && s.getLists().stream().anyMatch(l -> l.getId().equals(listId))) return;
        checkBoard(p, trello(() -> trello.getList(p.getUserId(), listId), "List"), "List");
    }

    private void ensureCard(Project p, String cardId) {
        BoardSnapshot s = snapshots.cached(p);
        if (s != null && s.getCards().stream().anyMatch(c -> c.getId().equals(cardId))) return;
        checkBoard(p, trello(() -> trello.getCard(p.getUserId(), cardId), "Card"), "Card");
    }

    private static void checkBoard(Project p, Map<String, Object> raw, String what) {
        if (raw == null || !p.getTrelloBoardId().equals(str(raw.get("idBoard")))) throw new NotFound(what);
    }

    private String completedList(Project p) {
        BoardSnapshot s = snapshots.cached(p);
        if (s == null) s = snapshots.get(p, false).snapshot();
        String done = null;
        for (ListView l : s.getLists()) {
            if (l.isClosed()) continue;
            if ("Completed".equalsIgnoreCase(l.getName())) return l.getId();
            if (done == null && "Done".equalsIgnoreCase(l.getName())) done = l.getId();
        }
        return done;
    }

    private static boolean hasDoneLabel(Map<String, Object> card) {
        return card.get("labels") instanceof List<?> ls && ls.stream().anyMatch(
                o -> o instanceof Map<?, ?> m && DONE_LABEL.equalsIgnoreCase(str(m.get("name"))));
    }

    /** Trello 404 on the addressed list/card -> our 404. */
    private static <T> T trello(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (TrelloGatewayException e) {
            if (e.getStatus() == 404) throw new NotFound(what);
            throw e;
        }
    }

    private static String requireName(Map<String, Object> body) {
        String n = str(body.get("name"));
        if (n == null || n.isBlank()) throw new IllegalArgumentException("name is required");
        return n;
    }

    private static String pos(Object o) {
        if (o instanceof Number n) return String.valueOf(n.doubleValue());
        if (o instanceof String s && (s.equals("top") || s.equals("bottom"))) return s;
        if (o instanceof String s) {
            try { Double.parseDouble(s); return s; } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("pos must be a number, \"top\" or \"bottom\"");
    }

    private static String bool(Object o) {
        if (o instanceof Boolean b) return b.toString();
        throw new IllegalArgumentException("boolean field expected");
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private Project owned(String id) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) throw new IllegalStateException("Not authenticated");
        Project p = repository.findByIdAndUserId(id, userId).orElseThrow(ProjectService.NotFoundException::new);
        if (p.getTrelloBoardId() == null || p.getTrelloBoardId().isBlank()) {
            throw new IllegalArgumentException("project has no Trello board");
        }
        return p;
    }
}
