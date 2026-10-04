package habitTracker.Project;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

// Hand-rolled RestTemplate client for mongo-backup's internal API (javaapp is memory-capped; no
// heavy client libs). Auth: X-Internal-Token. No retries.
@Component
@Profile("!stub")
public class HttpTrelloBoardGateway implements TrelloBoardGateway {

    static final String TOKEN_HEADER = "X-Internal-Token";
    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 30_000;
    private static final int PLAN_READ_MS = 6 * 60_000;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate shortClient;
    private final RestTemplate planClient;
    private final String baseUrl;
    private final String token;

    @Autowired
    public HttpTrelloBoardGateway(@Value("${trello.internal.base-url:http://mongo-backup:8092}") String baseUrl,
                                  @Value("${trello.internal.token:}") String token) {
        this(baseUrl, token, client(READ_MS), client(PLAN_READ_MS));
    }

    // Test constructor: inject (possibly the same) RestTemplate for MockRestServiceServer.
    HttpTrelloBoardGateway(String baseUrl, String token, RestTemplate shortClient, RestTemplate planClient) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("trello.internal.token (env INTERNAL_API_TOKEN) must be set");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.shortClient = shortClient;
        this.planClient = planClient;
    }

    private static RestTemplate client(int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_MS);
        f.setReadTimeout(readMs);
        return new RestTemplate(f);
    }

    @Override
    public String createBoard(String name) {
        Map<String, Object> res = call(shortClient, HttpMethod.POST, "/internal/boards", Map.of("name", name));
        Object id = res == null ? null : res.get("boardId");
        if (id == null || id.toString().isBlank()) {
            throw new TrelloGatewayException("Upstream returned no boardId", false, null);
        }
        return id.toString();
    }

    @Override
    public void deleteBoard(String boardId) {
        call(shortClient, HttpMethod.DELETE, "/internal/boards/" + boardId, null);
    }

    // No upstream endpoint to validate a board; adopt the id as given.
    @Override
    public String linkExisting(String boardId) {
        return boardId;
    }

    @Override
    public Map<String, Object> plan(String boardId, String description) {
        return call(planClient, HttpMethod.POST, "/internal/agent/plan",
                Map.of("boardId", boardId, "description", description));
    }

    @Override
    public Map<String, Object> apply(String boardId, Map<String, Object> body) {
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("boardId", boardId);
        if (body != null) {
            if (body.get("deadline") != null) req.put("deadline", body.get("deadline"));
            if (body.get("pace") != null) req.put("pace", body.get("pace"));
        }
        return call(shortClient, HttpMethod.POST, "/internal/agent/apply", req);
    }

    private Map<String, Object> call(RestTemplate rt, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.set(TOKEN_HEADER, token);
        if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
        try {
            return rt.exchange(baseUrl + path, method, new HttpEntity<>(body, h), MAP).getBody();
        } catch (HttpStatusCodeException e) {
            throw new TrelloGatewayException("Upstream " + e.getStatusCode().value() + " on " + method + " " + path,
                    e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT), e);
        } catch (RestClientException e) {
            throw new TrelloGatewayException("Upstream unreachable on " + method + " " + path, false, e);
        }
    }
}
