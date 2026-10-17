package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST {sandbox.url}/run to the sandbox sidecar. Sends only data (code, limits, allowed hosts, the
 * capability's declared secrets), never docker args. Any transport failure (sidecar down, 5xx, bad JSON) becomes SIDECAR_ERROR, not an
 * exception, so one failure can't abort the nightly loop.
 */
@Component
public class HttpSandboxClient implements SandboxClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSandboxClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public HttpSandboxClient(@Value("${sandbox.url:http://sandbox:8090}") String baseUrl,
                             @Value("${sandbox.read-timeout-seconds:150}") int readTimeoutSeconds) {
        // Read timeout must exceed script timeout + queue wait + image pull, else we give up early.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;
    }

    @Override
    public Result run(Request r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", r.userId());
        body.put("capabilityId", r.capabilityId());
        body.put("language", r.language());
        body.put("sourceCode", r.sourceCode());
        body.put("timeoutSeconds", r.timeoutSeconds());
        if (r.allowedHosts() != null && !r.allowedHosts().isEmpty()) body.put("allowedHosts", r.allowedHosts());
        if (r.secrets() != null && !r.secrets().isEmpty()) body.put("secrets", r.secrets());
        try {
            Map<String, Object> resp = restTemplate.exchange(baseUrl + "/run", HttpMethod.POST,
                    new HttpEntity<>(body), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
            if (resp == null) {
                return new Result(Outcome.SIDECAR_ERROR, null, "", "empty sidecar response");
            }
            Outcome outcome = Outcome.valueOf(String.valueOf(resp.get("outcome")));
            Integer exit = resp.get("exitCode") instanceof Number n ? n.intValue() : null;
            // The sidecar already redacts; this second pass is defence in depth before output is stored/logged.
            return new Result(outcome, exit, redact(str(resp.get("stdout")), r), redact(str(resp.get("stderr")), r));
        } catch (Exception e) {
            log.warn("[Sandbox] sidecar call failed: {}", e.toString());
            return new Result(Outcome.SIDECAR_ERROR, null, "", redact(String.valueOf(e.getMessage()), r));
        }
    }

    static String redact(String text, Request r) {
        if (r.secrets() == null || r.secrets().isEmpty()) return text;
        String out = text;
        for (String v : r.secrets().values().stream().filter(s -> s != null && !s.isEmpty())
                .sorted((a, b) -> b.length() - a.length()).toList()) {
            out = out.replace(v, "[REDACTED]");
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
