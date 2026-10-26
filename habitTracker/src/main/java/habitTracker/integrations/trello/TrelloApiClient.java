package habitTracker.integrations.trello;

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
import java.util.List;
import java.util.Map;

// Direct Trello REST client using the calling user's own apiKey+token (TrelloCredential).
// Hand-rolled RestTemplate (javaapp is memory-capped). No retries; Trello allows ~100 req/10s per token.
@Component
public class TrelloApiClient {

    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 15_000;

    private static final String BOARD_QS = "fields=name,url&lists=all&list_fields=name,pos,closed&cards=all"
            + "&card_fields=name,desc,idList,pos,due,dueComplete,closed,labels,badges,url,dateLastActivity";

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
            throw new TrelloGatewayException("Trello " + e.getStatusCode().value() + " on " + method + " " + path,
                    false, e, e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new TrelloGatewayException("Trello unreachable on " + method + " " + path, false, e);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
