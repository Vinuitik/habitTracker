package habitTracker.Project;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

// Assembles planner job prompts: template (resources/prompts/planner-*.md) + map + board + history + today.
// Placeholders are substituted in one pass, so user text containing "{{BOARD}}" is never expanded.
@Component
public class PlannerPrompts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]{4,}");

    private final String clarifyTemplate;
    private final String draftTemplate;
    private final int mapMaxChars;
    private final int boardMaxChars;
    private final int historyMaxChars;

    public PlannerPrompts(@Value("classpath:prompts/planner-clarify.md") Resource clarify,
                          @Value("classpath:prompts/planner-draft.md") Resource draft,
                          @Value("${relay.plan.map-max-chars:60000}") int mapMaxChars,
                          @Value("${relay.plan.board-max-chars:20000}") int boardMaxChars,
                          @Value("${relay.plan.history-max-chars:30000}") int historyMaxChars) {
        try {
            this.clarifyTemplate = clarify.getContentAsString(UTF_8);
            this.draftTemplate = draft.getContentAsString(UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.mapMaxChars = mapMaxChars;
        this.boardMaxChars = boardMaxChars;
        this.historyMaxChars = historyMaxChars;
    }

    public String build(PlannerConversation.Phase phase, Project p, ProjectMap map, BoardSnapshot board,
                        List<PlannerConversation.Message> messages, LocalDate today) {
        Map<String, String> vars = new HashMap<>();
        vars.put("TODAY", today.toString());
        vars.put("PROJECT_NAME", nz(p.getName()));
        vars.put("PROJECT_DESCRIPTION", nz(p.getDescription()));
        vars.put("MAP", mapSection(map, userText(messages), mapMaxChars));
        vars.put("BOARD", boardSection(board, boardMaxChars));
        vars.put("HISTORY", historySection(messages, historyMaxChars));
        String t = phase == PlannerConversation.Phase.DRAFT ? draftTemplate : clarifyTemplate;
        Matcher m = PLACEHOLDER.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = vars.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Index + every subsystem's notes, capped at maxChars. Over the cap, notes are dropped (name + paths kept)
     * starting with the subsystem least relevant to the conversation (keyword overlap with the user's messages;
     * ties: bigger notes go first). If still over, the text is truncated.
     */
    static String mapSection(ProjectMap map, String relevanceText, int maxChars) {
        if (map == null) return "(no repository map yet; explore the folder with the file tools)";
        List<ProjectMap.Subsystem> subs = map.getSubsystems() == null ? List.of() : map.getSubsystems();
        Set<String> words = words(relevanceText);
        int n = subs.size();
        boolean[] keepNotes = new boolean[n];
        Arrays.fill(keepNotes, true);
        String index = nz(map.getIndex());
        String text = renderMap(index, subs, keepNotes);
        if (text.length() > maxChars) {
            int[] score = new int[n];
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
                score[i] = relevance(subs.get(i), words);
            }
            order.sort(Comparator.<Integer>comparingInt(i -> score[i])
                    .thenComparing(i -> -nz(subs.get(i).getNotes()).length()));
            for (Integer i : order) {
                if (text.length() <= maxChars) break;
                keepNotes[i] = false;
                text = renderMap(index, subs, keepNotes);
            }
        }
        if (text.length() > maxChars) text = text.substring(0, Math.max(0, maxChars - 20)) + "\n…(map truncated)";
        return text;
    }

    private static String renderMap(String index, List<ProjectMap.Subsystem> subs, boolean[] keepNotes) {
        StringBuilder b = new StringBuilder(index.strip()).append("\n");
        for (int i = 0; i < subs.size(); i++) {
            ProjectMap.Subsystem s = subs.get(i);
            b.append("\n### Subsystem: ").append(nz(s.getName()));
            List<String> paths = s.getPaths() == null ? List.of() : s.getPaths();
            if (!paths.isEmpty()) b.append(" (").append(String.join(", ", paths)).append(")");
            b.append("\n");
            if (keepNotes[i]) b.append(nz(s.getNotes()).strip()).append("\n");
            else b.append("(notes omitted for size; read the files under these paths if needed)\n");
        }
        return b.toString();
    }

    private static int relevance(ProjectMap.Subsystem s, Set<String> words) {
        if (words.isEmpty()) return 0;
        Set<String> head = words(nz(s.getName()) + " " + String.join(" ", s.getPaths() == null ? List.of() : s.getPaths()));
        Set<String> notes = words(nz(s.getNotes()));
        int score = 0;
        for (String w : words) {
            if (head.contains(w)) score += 3;
            else if (notes.contains(w)) score += 1;
        }
        return score;
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Open lists with their open (not closed, not done) cards. */
    static String boardSection(BoardSnapshot board, int maxChars) {
        if (board == null) return "(board not available)";
        StringBuilder b = new StringBuilder();
        for (BoardSnapshot.ListView l : board.getLists()) {
            if (l.isClosed()) continue;
            b.append("## ").append(nz(l.getName())).append("\n");
            int open = 0;
            for (BoardSnapshot.CardView c : board.getCards()) {
                if (!Objects.equals(c.getIdList(), l.getId()) || c.isClosed() || c.isDone()) continue;
                open++;
                b.append("- ").append(nz(c.getName()));
                if (c.getDue() != null) b.append(" (due ").append(c.getDue(), 0, Math.min(10, c.getDue().length())).append(")");
                b.append("\n");
            }
            if (open == 0) b.append("(no open cards)\n");
        }
        if (b.isEmpty()) return "(board is empty)";
        String s = b.toString();
        return s.length() > maxChars ? s.substring(0, maxChars) + "\n…(board truncated)" : s;
    }

    /** Oldest messages are dropped first when over the cap; the newest one is always kept. */
    static String historySection(List<PlannerConversation.Message> messages, int maxChars) {
        Deque<String> parts = new ArrayDeque<>();
        int len = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            PlannerConversation.Message m = messages.get(i);
            StringBuilder b = new StringBuilder("ai".equals(m.getRole()) ? "Assistant: " : "User: ").append(nz(m.getText()));
            if (m.getQuestions() != null && !m.getQuestions().isEmpty()) {
                b.append("\nQuestions asked:");
                for (String q : m.getQuestions()) b.append("\n- ").append(q);
            }
            String part = b.append("\n\n").toString();
            if (len + part.length() > maxChars && !parts.isEmpty()) {
                parts.addFirst("(earlier messages omitted)\n\n");
                break;
            }
            parts.addFirst(part);
            len += part.length();
        }
        return String.join("", parts).strip();
    }

    private static String userText(List<PlannerConversation.Message> messages) {
        StringBuilder b = new StringBuilder();
        for (PlannerConversation.Message m : messages) if ("user".equals(m.getRole())) b.append(m.getText()).append(' ');
        return b.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
