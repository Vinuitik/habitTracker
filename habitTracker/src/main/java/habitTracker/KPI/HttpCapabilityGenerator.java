package habitTracker.KPI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST mongo-backup:8092/internal/agent/capability (same internal API and base URL as
 * habitTracker.Project.HttpTrelloBoardGateway's /internal/agent/plan). No auth (docker network
 * only), no retries. Read timeout covers a `claude -p` run (upstream CLI timeout is 300s).
 */
@Component
public class HttpCapabilityGenerator implements CapabilityGenerator {

    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 6 * 60_000;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public HttpCapabilityGenerator(@Value("${trello.internal.base-url:http://mongo-backup:8092}") String baseUrl) {
        this(baseUrl, client());
    }

    // Test constructor: inject a RestTemplate bound to MockRestServiceServer.
    HttpCapabilityGenerator(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_MS);
        f.setReadTimeout(READ_MS);
        return new RestTemplate(f);
    }

    @Override
    public Proposal propose(String kpiName, String description, List<CapabilityTurn> conversation,
                            List<CapabilityOption> previousOptions) {
        Map<String, Object> body = base("propose", kpiName, description);
        if (!conversation.isEmpty()) body.put("conversation", conversation);
        if (!previousOptions.isEmpty()) body.put("previousOptions", previousOptions);
        Map<String, Object> res = call(body);

        List<CapabilityOption> options = new ArrayList<>();
        if (res.get("options") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && str(m, "summary") != null) options.add(option(m));
            }
        } else if (str(res, "proposal") != null) { // single-proposal reply shape (pre-options upstream)
            options.add(CapabilityOption.of(str(res, "proposal"), platform(res), declared(res)));
        }
        if (options.isEmpty()) throw new CapabilityGeneratorException("Upstream returned no options", false, null);
        return new Proposal(str(res, "reply"), options);
    }

    private static CapabilityOption option(Map<?, ?> m) {
        List<String> sources = new ArrayList<>();
        if (m.get("sources") instanceof List<?> l) l.forEach(v -> sources.add(String.valueOf(v)));
        return new CapabilityOption(str(m, "title"), str(m, "summary"), platform(m), declared(m),
                label(m, "cost"), str(m, "costNote"), str(m, "access"), label(m, "method"),
                label(m, "difficulty"), str(m, "difficultyNote"), sources, null);
    }

    private static Map<String, List<String>> declared(Map<?, ?> m) {
        Map<String, List<String>> declared = new HashMap<>();
        for (String key : List.of("network", "secrets")) {
            List<String> vals = new ArrayList<>();
            if (m.get("declaredCapabilities") instanceof Map<?, ?> d && d.get(key) instanceof List<?> l) {
                l.forEach(v -> vals.add(String.valueOf(v)));
            }
            declared.put(key, vals);
        }
        return declared;
    }

    private static String platform(Map<?, ?> m) {
        String p = str(m, "platform");
        return p == null ? "SERVER" : p;
    }

    private static String label(Map<?, ?> m, String key) {
        String v = str(m, key);
        return v == null ? "UNKNOWN" : v.toUpperCase();
    }

    private static String str(Map<?, ?> m, String key) {
        return m.get(key) instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    @Override
    public String generate(String kpiName, String description, String proposal,
                           Map<String, List<String>> declaredCapabilities, String previousCode, String previousError) {
        Map<String, Object> body = base("generate", kpiName, description);
        body.put("proposal", proposal);
        body.put("declaredCapabilities", declaredCapabilities);
        if (previousCode != null) body.put("previousCode", previousCode);
        if (previousError != null) body.put("previousError", previousError);
        Object code = call(body).get("sourceCode");
        if (!(code instanceof String s) || s.isBlank()) {
            throw new CapabilityGeneratorException("Upstream returned no sourceCode", false, null);
        }
        return s;
    }

    private static Map<String, Object> base(String mode, String kpiName, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("kpiName", kpiName);
        body.put("description", description);
        return body;
    }

    private Map<String, Object> call(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            Map<String, Object> res = restTemplate.exchange(baseUrl + "/internal/agent/capability",
                    HttpMethod.POST, new HttpEntity<>(body, h), MAP).getBody();
            if (res == null) throw new CapabilityGeneratorException("Upstream returned an empty body", false, null);
            return res;
        } catch (HttpStatusCodeException e) {
            boolean busy = e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT);
            throw new CapabilityGeneratorException(busy
                    ? "Another generation is already running, try again in a minute"
                    : "Generator upstream " + e.getStatusCode().value() + ": " + truncate(e.getResponseBodyAsString()),
                    busy, e);
        } catch (RestClientException e) {
            throw new CapabilityGeneratorException("Generator unreachable", false, e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
