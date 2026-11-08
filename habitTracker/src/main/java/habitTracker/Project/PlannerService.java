package habitTracker.Project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import habitTracker.auth.SecurityUtils;
import habitTracker.integrations.trello.TrelloApiClient;
import habitTracker.integrations.trello.TrelloGatewayException;
import habitTracker.relay.RunnerCallException;
import habitTracker.relay.UsageLimit;
import habitTracker.aiusage.AiUsageService;
import habitTracker.relay.RunnerJobService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

// Planner chat on the user's runner: CLARIFY jobs (questions) → /lock → DRAFT job (lists/cards/schedule) → /apply (Trello).
// Ownership like ProjectService: project by findByIdAndUserId, conversation by (id, projectId, userId) → else 404.
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);
    static final int MAX_MESSAGE_CHARS = 8000;
    static final int MAX_QUESTIONS = 3;
    static final int MAX_LISTS = 20;
    static final int MAX_CARDS = 150;

    private final ProjectRepository projects;
    private final ProjectMapRepository maps;
    private final PlannerConversationRepository conversations;
    private final BoardSnapshotService boardSnapshots;
    private final TrelloApiClient trello;
    private final RunnerJobService runner;
    private final AiUsageService usage;
    private final PlannerPrompts prompts;
    private final MongoTemplate mongo;
    private final ObjectMapper json;
    private final int maxTurns;

    public PlannerService(ProjectRepository projects, ProjectMapRepository maps,
                          PlannerConversationRepository conversations, BoardSnapshotService boardSnapshots,
                          TrelloApiClient trello, RunnerJobService runner, AiUsageService usage, PlannerPrompts prompts,
                          MongoTemplate mongo, ObjectMapper json,
                          @Value("${relay.plan.max-turns:80}") int maxTurns) {
        this.projects = projects;
        this.maps = maps;
        this.conversations = conversations;
        this.boardSnapshots = boardSnapshots;
        this.trello = trello;
        this.runner = runner;
        this.usage = usage;
        this.prompts = prompts;
        this.mongo = mongo;
        this.json = json;
        this.maxTurns = maxTurns;
    }

    public static class BusyException extends RuntimeException {
        public BusyException() { super("a planner job is still running"); }
    }

    public static class AlreadyAppliedException extends RuntimeException {
        public AlreadyAppliedException() { super("this plan was already added to the board"); }
    }

    public record Started(String conversationId, String jobId) {}

    @PostConstruct
    void init() {
        mongo.indexOps(PlannerConversation.class).ensureIndex(
                new Index().on("projectId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC));
    }

    // ---- start jobs ----

    /** New conversation (conversationId null) or follow-up message; always a CLARIFY job. Nothing is saved if the runner refuses. */
    public Started message(String projectId, String conversationId, String message) {
        Project p = linked(owned(projectId));
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("message is required");
        if (text.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("message is too long");
        PlannerConversation c;
        if (conversationId == null || conversationId.isBlank()) {
            c = new PlannerConversation();
            c.setProjectId(p.getId());
            c.setUserId(p.getUserId());
            c.setCreatedAt(Instant.now());
        } else {
            c = ownedConversation(p, conversationId);
            ensureIdle(c);
        }
        c.getMessages().add(new PlannerConversation.Message("user", text, null));
        c.setPhase(PlannerConversation.Phase.CLARIFY);
        return startJob(p, c);
    }

    /** User is done clarifying → DRAFT job over the same history. */
    public Started lock(String projectId, String conversationId) {
        Project p = linked(owned(projectId));
        PlannerConversation c = ownedConversation(p, conversationId);
        ensureIdle(c);
        c.setPhase(PlannerConversation.Phase.DRAFT);
        return startJob(p, c);
    }

    private Started startJob(Project p, PlannerConversation c) {
        String prompt = prompts.build(c.getPhase(), p, maps.findByProjectId(p.getId()).orElse(null),
                boardForPrompt(p), c.getMessages(), LocalDate.now());
        String jobId = runner.startJob(p.getUserId(), p.getRunnerDeviceId(),
                new RunnerJobService.JobSpec("plan", p.getRunnerProjectId(), prompt, null, maxTurns));
        c.setCurrentJobId(jobId);
        c.setError(null);
        c.setUpdatedAt(Instant.now());
        c = conversations.save(c);
        return new Started(c.getId(), jobId);
    }

    // Board context: cached/refreshed snapshot; any Trello failure → whatever is cached (or none).
    private BoardSnapshot boardForPrompt(Project p) {
        try {
            return boardSnapshots.get(p, false).snapshot();
        } catch (RuntimeException e) {
            log.info("[planner] project {}: board unavailable for prompt ({})", p.getId(), e.toString());
            return boardSnapshots.cached(p);
        }
    }

    // ---- poll ----

    /** Polls the current job (never an older one); on a finished job stores its outcome once. */
    public Map<String, Object> poll(String projectId, String conversationId, long after) {
        Project p = owned(projectId);
        PlannerConversation c = ownedConversation(p, conversationId);
        List<Map<String, Object>> events = new ArrayList<>();
        long nextSeq = Math.max(0, after);
        AiUsageService.Tokens finishedUsage = null;
        String jobId = c.getCurrentJobId();
        if (jobId != null && !jobId.equals(c.getResultJobId())) {
            linked(p);
            JsonNode snap;
            try {
                snap = runner.pollJob(p.getUserId(), p.getRunnerDeviceId(), jobId, after);
            } catch (RunnerCallException e) {
                // Runner forgot the job (restart / relink): mark failed so the conversation isn't stuck "running".
                if (e.getStatus() != 404) throw e;
                storeFailure(c, jobId, "planner job no longer exists on the runner");
                return view(conversations.findById(c.getId()).orElse(c), events, nextSeq);
            }
            for (JsonNode ev : snap.path("events")) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("seq", ev.path("seq").asLong());
                e.put("kind", ev.path("kind").asText("text"));
                e.put("text", ev.path("text").asText(""));
                events.add(e);
            }
            nextSeq = snap.path("nextSeq").asLong(nextSeq);
            String state = snap.path("state").asText("");
            usage.record(p.getUserId(), jobId, snap, AiUsageService.Source.plan(p.getId()));
            finishedUsage = AiUsageService.isTerminal(state) ? AiUsageService.tokens(snap.get("usage")).orElse(null) : null;
            if ("done".equals(state)) {
                storeResult(c, jobId, snap.get("result"));
                c = conversations.findById(c.getId()).orElse(c);
            } else if (isFailed(state)) {
                String err = UsageLimit.message(snap).orElse(
                        snap.path("error").isTextual() ? snap.path("error").asText() : "planner job " + state);
                storeFailure(c, jobId, err);
                c = conversations.findById(c.getId()).orElse(c);
            }
        }
        Map<String, Object> out = view(c, events, nextSeq);
        // Only on the poll that sees the job finish: the UI shows "Used N tokens" under that job.
        if (finishedUsage != null) {
            out.put("usage", Map.of("inputTokens", finishedUsage.input(), "outputTokens", finishedUsage.output(),
                    "totalTokens", finishedUsage.total()));
        }
        return out;
    }

    private static boolean isFailed(String state) {
        return "failed".equals(state) || "error".equals(state) || "cancelled".equals(state) || "canceled".equals(state);
    }

    private void storeResult(PlannerConversation c, String jobId, JsonNode result) {
        Update u = new Update().set("resultJobId", jobId).set("updatedAt", Instant.now());
        try {
            JsonNode root = extractObject(result);
            String reply = root.path("reply").asText("").strip();
            if (c.getPhase() == PlannerConversation.Phase.DRAFT) {
                PlannerConversation.Draft draft = parseDraft(root.path("draft"));
                u.set("draft", draft).set("questions", List.of()).set("error", null)
                        .push("messages", new PlannerConversation.Message("ai", reply.isEmpty() ? "Draft ready." : reply, null));
            } else {
                List<String> qs = parseQuestions(root.path("questions"));
                if (reply.isEmpty() && qs.isEmpty()) throw new IllegalArgumentException("result has neither reply nor questions");
                u.set("questions", qs).set("error", null)
                        .push("messages", new PlannerConversation.Message("ai", reply, qs.isEmpty() ? null : qs));
            }
        } catch (IllegalArgumentException e) {
            log.warn("[planner] conversation {} job {}: unusable result: {}", c.getId(), jobId, e.getMessage());
            u.set("error", "unusable result: " + e.getMessage());
        }
        applyOnce(c, jobId, u);
    }

    private void storeFailure(PlannerConversation c, String jobId, String error) {
        applyOnce(c, jobId, new Update().set("resultJobId", jobId).set("error", error).set("updatedAt", Instant.now()));
    }

    // Conditional update: only while jobId is still current and not yet stored, so two concurrent polls
    // can't append the AI message twice and a newer job's state is never overwritten.
    private void applyOnce(PlannerConversation c, String jobId, Update u) {
        Query q = new Query(Criteria.where("_id").is(c.getId()).and("currentJobId").is(jobId).and("resultJobId").ne(jobId));
        mongo.updateFirst(q, u, PlannerConversation.class);
    }

    private Map<String, Object> view(PlannerConversation c, List<Map<String, Object>> events, long nextSeq) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state(c));
        out.put("events", events);
        out.put("nextSeq", nextSeq);
        List<Map<String, String>> msgs = new ArrayList<>();
        for (PlannerConversation.Message m : c.getMessages()) msgs.add(Map.of("role", m.getRole(), "text", m.getText() == null ? "" : m.getText()));
        out.put("messages", msgs);
        if (c.getQuestions() != null && !c.getQuestions().isEmpty()) out.put("questions", c.getQuestions());
        if (c.getDraft() != null) out.put("draft", c.getDraft());
        if (c.getError() != null) out.put("error", c.getError());
        return out;
    }

    static String state(PlannerConversation c) {
        String job = c.getCurrentJobId();
        if (job != null && !job.equals(c.getResultJobId())) return "running";
        if (c.getError() != null) return "failed";
        if (c.getPhase() == PlannerConversation.Phase.DRAFT && c.getDraft() != null) return "drafted";
        return "waiting_user";
    }

    // ---- result parsing (tolerates prose / code fences around the JSON: outermost { … }) ----

    JsonNode extractObject(JsonNode result) {
        if (result == null || result.isNull()) throw new IllegalArgumentException("job has no result");
        JsonNode root = result;
        if (result.isTextual()) {
            String t = result.asText();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a < 0 || b <= a) throw new IllegalArgumentException("result contains no JSON object");
            try {
                root = json.readTree(t.substring(a, b + 1));
            } catch (IOException e) {
                throw new IllegalArgumentException("result JSON does not parse");
            }
        }
        if (!root.isObject()) throw new IllegalArgumentException("result is not a JSON object");
        return root;
    }

    static List<String> parseQuestions(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode q : node) {
            String s = q.isTextual() ? q.asText().strip() : "";
            if (!s.isEmpty() && out.size() < MAX_QUESTIONS) out.add(s);
        }
        return out;
    }

    /** Validates a draft (from the agent or from the apply body). Bad schedule rows are skipped; bad lists/cards fail. */
    static PlannerConversation.Draft parseDraft(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("draft is missing");
        JsonNode lists = node.path("lists");
        if (!lists.isArray() || lists.isEmpty()) throw new IllegalArgumentException("draft has no lists");
        if (lists.size() > MAX_LISTS) throw new IllegalArgumentException("draft has too many lists");
        PlannerConversation.Draft d = new PlannerConversation.Draft();
        int cards = 0;
        for (JsonNode l : lists) {
            String name = l.path("name").asText("").strip();
            if (name.isEmpty()) throw new IllegalArgumentException("draft list without a name");
            PlannerConversation.DraftList dl = new PlannerConversation.DraftList(name, new ArrayList<>());
            for (JsonNode cn : l.path("cards")) {
                String title = cn.path("title").asText("").strip();
                if (title.isEmpty()) throw new IllegalArgumentException("draft card without a title");
                if (++cards > MAX_CARDS) throw new IllegalArgumentException("draft has too many cards");
                String desc = cn.path("desc").asText("");
                dl.getCards().add(new PlannerConversation.DraftCard(title, desc,
                        importance(cn.path("importance")), date(cn.path("due").asText(null))));
            }
            d.getLists().add(dl);
        }
        for (JsonNode s : node.path("schedule")) {
            String date = date(s.path("date").asText(null));
            String title = s.path("title").asText("").strip();
            if (date == null || title.isEmpty()) continue;
            d.getSchedule().add(new PlannerConversation.ScheduleItem(date, title, importance(s.path("importance"))));
        }
        return d;
    }

    // 1..3 (Could/Should/Must); words accepted; anything else → 2.
    static int importance(JsonNode n) {
        if (n.isNumber()) return Math.max(1, Math.min(3, n.asInt()));
        String s = n.asText("").strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "3", "must", "high" -> 3;
            case "1", "could", "low" -> 1;
            default -> 2;
        };
    }

    // YYYY-MM-DD (a longer ISO timestamp is cut to its date); invalid → null.
    static String date(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        if (t.length() > 10) t = t.substring(0, 10);
        try {
            return LocalDate.parse(t).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---- apply (the only Trello write) ----

    /**
     * Writes the draft (body draft if given, else the stored one) to the project's board: a draft list whose name
     * matches an open board list (case-insensitive) is reused, else created. Card due = card.due, else its schedule
     * date (matched by title), else the deadline. Importance goes into a ```meta block in the description.
     */
    public int apply(String projectId, String conversationId, JsonNode draftNode, String deadline, String pace) {
        Project p = owned(projectId);
        PlannerConversation c = ownedConversation(p, conversationId);
        ensureIdle(c);
        if (c.getAppliedAt() != null) throw new AlreadyAppliedException();  // a second apply would duplicate every card
        if (p.getTrelloBoardId() == null) throw new IllegalArgumentException("project has no Trello board");
        PlannerConversation.Draft draft = draftNode != null && !draftNode.isNull() ? parseDraft(draftNode) : c.getDraft();
        if (draft == null) throw new IllegalArgumentException("no draft to apply");
        String fallbackDue = date(deadline);
        if (deadline != null && !deadline.isBlank() && fallbackDue == null) throw new IllegalArgumentException("deadline must be YYYY-MM-DD");

        Map<String, String> scheduled = new HashMap<>();
        for (PlannerConversation.ScheduleItem s : draft.getSchedule()) scheduled.putIfAbsent(key(s.getTitle()), s.getDate());
        Map<String, String> existingLists = new HashMap<>();
        BoardSnapshot snap = boardSnapshots.cached(p);
        if (snap != null) for (BoardSnapshot.ListView l : snap.getLists()) {
            if (!l.isClosed()) existingLists.putIfAbsent(key(l.getName()), l.getId());
        }

        String uid = p.getUserId();
        int created = 0;
        for (PlannerConversation.DraftList dl : draft.getLists()) {
            String listId = existingLists.get(key(dl.getName()));
            if (listId == null) {
                Object id = trello.createList(uid, p.getTrelloBoardId(), dl.getName(), "bottom").get("id");
                if (id == null) throw new TrelloGatewayException("Trello returned no list id", false, null, 502);
                listId = id.toString();
                existingLists.put(key(dl.getName()), listId);
            }
            for (PlannerConversation.DraftCard card : dl.getCards()) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("idList", listId);
                params.put("name", card.getTitle());
                params.put("desc", withMeta(card.getDesc(), card.getImportance()));
                params.put("pos", "bottom");
                String due = card.getDue() != null ? card.getDue() : scheduled.getOrDefault(key(card.getTitle()), fallbackDue);
                if (due != null) params.put("due", due + "T12:00:00.000Z");
                trello.createCard(uid, params);
                created++;
            }
        }
        c.setDraft(draft);
        c.setAppliedAt(Instant.now());
        c.setAppliedDeadline(fallbackDue);
        c.setAppliedPace(pace);
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
        try {
            boardSnapshots.get(p, true);
        } catch (RuntimeException e) {
            log.info("[planner] project {}: snapshot refresh after apply failed ({})", p.getId(), e.toString());
        }
        return created;
    }

    // Same ```meta block the Trello MCP reads (importance: 1..3).
    static String withMeta(String desc, Integer importance) {
        String body = desc == null ? "" : desc.strip();
        if (importance == null) return body;
        String block = "```meta\nimportance: " + importance + "\n```";
        return body.isEmpty() ? block : body + "\n\n" + block;
    }

    private static String key(String s) { return s == null ? "" : s.strip().toLowerCase(Locale.ROOT); }

    // ---- helpers ----

    private void ensureIdle(PlannerConversation c) {
        if (c.getCurrentJobId() != null && !c.getCurrentJobId().equals(c.getResultJobId())) throw new BusyException();
    }

    private PlannerConversation ownedConversation(Project p, String conversationId) {
        return conversations.findByIdAndProjectIdAndUserId(conversationId, p.getId(), p.getUserId())
                .orElseThrow(() -> new ProjectService.NotFoundException("conversation not found"));
    }

    private Project linked(Project p) {
        if (p.getRunnerDeviceId() == null || p.getRunnerProjectId() == null) throw new ProjectRunnerService.NotLinkedException();
        return p;
    }

    private Project owned(String id) {
        String uid = SecurityUtils.getCurrentUserId();
        if (uid == null) throw new IllegalStateException("Not authenticated");
        return projects.findByIdAndUserId(id, uid).orElseThrow(ProjectService.NotFoundException::new);
    }
}
