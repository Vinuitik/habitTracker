package habitTracker.integrations.trello;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Direct Trello REST client using the calling user's own apiKey+token (TrelloCredential).
// Hand-rolled RestTemplate (javaapp is memory-capped). No retries; Trello allows ~100 req/10s per token.
@Component
public class TrelloApiClient {

    private static final Logger log = LoggerFactory.getLogger(TrelloApiClient.class);
    private static final int LOG_MSG_MAX = 200;

    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 15_000;

    private static final String CARD_FIELDS =
            "name,desc,idList,idBoard,pos,due,dueComplete,closed,labels,badges,url,dateLastActivity";
    private static final String BOARD_QS = "fields=name,url,dateLastActivity&lists=all&list_fields=name,pos,closed&cards=all"
            + "&card_fields=" + CARD_FIELDS;

    private final TrelloCredentialRepository credentials;
    private final RestTemplate rest;
    private final String baseUrl;

    @Autowired
    public TrelloApiClient(TrelloCredentialRepository credentials,
                           @Value("${trello.api.base-url:https://api.trello.com}") String baseUrl) {
        this(credentials, client(), baseUrl);
    }

    // Test constructor: inject a RestTemplate bound to MockRestServiceServer.
    TrelloApiClient(TrelloCredentialRepository credentials, RestTemplate rest, String baseUrl) {
        this.credentials = credentials;
        this.rest = rest;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_MS);
        f.setReadTimeout(READ_MS);
        return new RestTemplate(f);
    }

    /** Open boards of the user: [{id,name,url,closed}]. */
    public List<Map<String, Object>> listMyBoards(String userId) {
        return call(userId, HttpMethod.GET, "/1/members/me/boards", "filter=open&fields=name,url,closed",
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /** Board with nested lists[] and cards[] (raw Trello JSON). */
    public Map<String, Object> getBoard(String userId, String boardId) {
        return call(userId, HttpMethod.GET, "/1/boards/" + boardId, BOARD_QS,
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Cheap change probe: board.dateLastActivity (ISO string) or null if Trello omitted it. 404 → TrelloGatewayException(status 404). */
    public String getBoardActivity(String userId, String boardId) {
        Map<String, Object> res = call(userId, HttpMethod.GET, "/1/boards/" + boardId, "fields=dateLastActivity", MAP);
        Object d = res == null ? null : res.get("dateLastActivity");
        return d == null ? null : d.toString();
    }

    /** Creates an empty board (no default lists); returns its id. */
    public String createBoard(String userId, String name) {
        Map<String, Object> res = call(userId, HttpMethod.POST, "/1/boards", "name={name}&defaultLists=false",
                new ParameterizedTypeReference<Map<String, Object>>() {}, Map.of("name", name));
        Object id = res == null ? null : res.get("id");
        if (id == null || id.toString().isBlank()) throw new TrelloGatewayException("Trello returned no board id", false, null);
        return id.toString();
    }

    public void deleteBoard(String userId, String boardId) {
        call(userId, HttpMethod.DELETE, "/1/boards/" + boardId, null,
                new ParameterizedTypeReference<Object>() {});
    }

    // ---- write-through editing (one Trello call each; params are only what the caller provides) ----

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** POST /1/lists; returns list JSON {id,name,pos,closed,idBoard}. */
    public Map<String, Object> createList(String userId, String boardId, String name, String pos) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("idBoard", boardId); v.put("name", name); v.put("pos", pos == null ? "bottom" : pos);
        return call(userId, HttpMethod.POST, "/1/lists", qs(v), MAP, v);
    }

    /** PUT /1/lists/{id} with only the given params (name,pos,closed). */
    public Map<String, Object> updateList(String userId, String listId, Map<String, String> params) {
        return call(userId, HttpMethod.PUT, "/1/lists/" + listId, qs(params), MAP, params);
    }

    /** PUT /1/lists/{id}/idBoard?value={targetBoardId}[&pos=] : moves the list (with its cards) to another board. */
    public Map<String, Object> moveListToBoard(String userId, String listId, String targetBoardId, String pos) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("value", targetBoardId);
        if (pos != null) v.put("pos", pos);
        return call(userId, HttpMethod.PUT, "/1/lists/" + listId + "/idBoard", qs(v), MAP, v);
    }

    public Map<String, Object> getList(String userId, String listId) {
        return call(userId, HttpMethod.GET, "/1/lists/" + listId, "fields=name,pos,closed,idBoard", MAP);
    }

    /** POST /1/cards (params: idList,name,desc?,pos); returns full card JSON. */
    public Map<String, Object> createCard(String userId, Map<String, String> params) {
        return call(userId, HttpMethod.POST, "/1/cards", qs(params), MAP, params);
    }

    /** PUT /1/cards/{id} with only the given params; value "null" clears (due). */
    public Map<String, Object> updateCard(String userId, String cardId, Map<String, String> params) {
        return call(userId, HttpMethod.PUT, "/1/cards/" + cardId, qs(params), MAP, params);
    }

    public Map<String, Object> getCard(String userId, String cardId) {
        return call(userId, HttpMethod.GET, "/1/cards/" + cardId, "fields=" + CARD_FIELDS, MAP);
    }

    public List<Map<String, Object>> getBoardLabels(String userId, String boardId) {
        return call(userId, HttpMethod.GET, "/1/boards/" + boardId + "/labels", "fields=name,color&limit=1000",
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Map<String, Object> createLabel(String userId, String boardId, String name, String color) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", name); v.put("color", color); v.put("idBoard", boardId);
        return call(userId, HttpMethod.POST, "/1/labels", qs(v), MAP, v);
    }

    public void addCardLabel(String userId, String cardId, String labelId) {
        call(userId, HttpMethod.POST, "/1/cards/" + cardId + "/idLabels", "value={value}",
                new ParameterizedTypeReference<Object>() {}, Map.of("value", labelId));
    }

    public void removeCardLabel(String userId, String cardId, String labelId) {
        call(userId, HttpMethod.DELETE, "/1/cards/" + cardId + "/idLabels/" + labelId, null,
                new ParameterizedTypeReference<Object>() {});
    }

    private static String qs(Map<String, String> params) {
        StringBuilder b = new StringBuilder();
        for (String k : params.keySet()) b.append(b.length() == 0 ? "" : "&").append(k).append("={").append(k).append("}");
        return b.toString();
    }

    private <T> T call(String userId, HttpMethod method, String path, String qs, ParameterizedTypeReference<T> type) {
        return call(userId, method, path, qs, type, Map.of());
    }

    private <T> T call(String userId, HttpMethod method, String path, String qs, ParameterizedTypeReference<T> type,
                       Map<String, String> vars) {
        TrelloCredential c = credentials.findByUserId(userId).orElseThrow(TrelloNotConnectedException::new);
        if (isBlank(c.getApiKey()) || isBlank(c.getToken())) throw new TrelloNotConnectedException();

        Map<String, String> all = new HashMap<>(vars);
        all.put("key", c.getApiKey());
        all.put("token", c.getToken());
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + path + "?" + (qs == null ? "" : qs + "&") + "key={key}&token={token}")
                .encode().build(all);
        try {
            return rest.exchange(uri, method, HttpEntity.EMPTY, type).getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) throw new TrelloAuthException();
            log.warn("Trello HTTP {} on {} {}: {}", e.getStatusCode().value(), method, path, rootCause(e));
            throw new TrelloGatewayException("Trello " + e.getStatusCode().value() + " on " + method + " " + path,
                    false, e, e.getStatusCode().value(),
                    e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
        } catch (RestClientException e) {
            String cause = rootCause(e);
            log.warn("Trello unreachable on {} {}: {}", method, path, cause);
            throw new TrelloGatewayException("Trello unreachable on " + method + " " + path + " (" + cause + ")", false, e);
        }
    }

    /** "SimpleName: message" of the root cause, message truncated. The request URI (key/token) is never part of it. */
    private static String rootCause(RestClientException e) {
        Throwable t = e.getMostSpecificCause();
        String m = t.getMessage();
        if (m != null && m.length() > LOG_MSG_MAX) m = m.substring(0, LOG_MSG_MAX) + "...";
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
