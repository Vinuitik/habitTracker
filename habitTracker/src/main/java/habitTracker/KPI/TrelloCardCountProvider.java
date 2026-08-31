package habitTracker.KPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Real ProxyType.TRELLO_CARD_COUNT provider (M2), replacing the M1 StubTrelloCardCountProxyProvider
 * (see that class's former javadoc: "swap this bean for a real Trello-calling ProxyProvider of the
 * same type ... the replacement is a drop-in" — ProxyProviderRegistry only ever sees one bean per
 * ProxyType, so removing the stub and adding this @Component is the whole swap).
 *
 * Counts distinct cards moved into a configured "done" list on the given date, via Trello's
 * actions API: GET /1/lists/{listId}/actions?filter=updateCard&since=<dayStart>&before=<dayEnd>.
 * That endpoint already scopes to the list, but its feed includes cards moved OUT of the list too
 * (both directions touch the list), so each action's data.listAfter.id is cross-checked against
 * listId, and data.card.id is used to dedupe a card moved into the list more than once on the same
 * day down to a single count (see TrelloCardCountProviderTest for the exact fixtures this covers).
 *
 * Auth: apiKey+token come from TrelloCredentialRepository, keyed by kpi.getUserId() — a Trello API
 * key+token pair the user pastes in themselves, deliberately not the app's own session/JWT auth
 * (see TrelloCredential's javadoc). boardId/listId come from kpi.getProxyConfig() (per-KPI, same
 * mechanism every other proxyConfig-driven provider would use) so one user can point different
 * KPIs at different Trello lists while sharing one Trello connection.
 *
 * Anything that would otherwise throw — missing/invalid credentials, missing proxyConfig, a
 * network error, a non-2xx Trello response, unparseable JSON — is caught here and turned into
 * Optional.empty() plus a log line, never propagated. That matters: KPIProxyFillService loops over
 * every user's active KPIs in one nightly pass, so one user's bad/expired Trello token must not
 * throw and abort that loop before it reaches everyone else's KPIs.
 */
@Component
public class TrelloCardCountProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(TrelloCardCountProvider.class);

    private final TrelloCredentialRepository credentialRepository;
    private final RestTemplate restTemplate;
    private final String apiBaseUrl;

    @Autowired
    public TrelloCardCountProvider(TrelloCredentialRepository credentialRepository,
                                    @Value("${trello.api.base-url:https://api.trello.com}") String apiBaseUrl) {
        this(credentialRepository, new RestTemplate(), apiBaseUrl);
    }

    // Package-private test constructor: injects a mockable RestTemplate and lets tests point
    // apiBaseUrl at a local stub server instead of the real Trello host.
    TrelloCardCountProvider(TrelloCredentialRepository credentialRepository, RestTemplate restTemplate,
                             String apiBaseUrl) {
        this.credentialRepository = credentialRepository;
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public ProxyType getType() {
        return ProxyType.TRELLO_CARD_COUNT;
    }

    @Override
    public Optional<Double> fetchValue(KPI kpi, LocalDate date) {
        String userId = kpi.getUserId();
        if (userId == null) {
            log.warn("[Trello] KPI {} has no userId — skipping", kpi.getId());
            return Optional.empty();
        }

        Optional<TrelloCredential> credential = credentialRepository.findByUserId(userId);
        if (credential.isEmpty() || isBlank(credential.get().getApiKey()) || isBlank(credential.get().getToken())) {
            log.warn("[Trello] no Trello credentials configured for user {} — skipping KPI '{}'", userId, kpi.getName());
            return Optional.empty();
        }

        Map<String, String> config = kpi.getProxyConfig();
        String listId = config != null ? config.get("listId") : null;
        if (isBlank(listId)) {
            log.warn("[Trello] KPI '{}' has no proxyConfig.listId — skipping", kpi.getName());
            return Optional.empty();
        }

        try {
            return Optional.of((double) countCardsMovedIn(credential.get(), listId, date));
        } catch (RestClientException e) {
            log.warn("[Trello] request failed for user {} KPI '{}': {}", userId, kpi.getName(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[Trello] unexpected error for user {} KPI '{}': {}", userId, kpi.getName(), e.toString());
            return Optional.empty();
        }
    }

    private int countCardsMovedIn(TrelloCredential credential, String listId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        URI uri = UriComponentsBuilder.fromHttpUrl(apiBaseUrl + "/1/lists/{listId}/actions")
                .queryParam("filter", "updateCard")
                .queryParam("since", dayStart.toString())
                .queryParam("before", dayEnd.toString())
                .queryParam("key", credential.getApiKey())
                .queryParam("token", credential.getToken())
                .buildAndExpand(listId)
                .encode()
                .toUri();

        ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                uri, HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        List<Map<String, Object>> actions = resp.getBody();
        if (actions == null || actions.isEmpty()) {
            return 0;
        }

        Set<String> cardIdsMovedIn = new LinkedHashSet<>();
        for (Map<String, Object> action : actions) {
            Object dataObj = action.get("data");
            if (!(dataObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataObj;

            String listAfterId = nestedId(data.get("listAfter"));
            if (listAfterId == null || !listAfterId.equals(listId)) continue; // moved OUT of the list, not in

            String cardId = nestedId(data.get("card"));
            if (cardId == null) continue;

            Instant actionDate = parseInstant(action.get("date"));
            if (actionDate == null || actionDate.isBefore(dayStart) || !actionDate.isBefore(dayEnd)) continue;

            cardIdsMovedIn.add(cardId);
        }
        return cardIdsMovedIn.size();
    }

    @SuppressWarnings("unchecked")
    private String nestedId(Object ref) {
        if (!(ref instanceof Map)) return null;
        Object id = ((Map<String, Object>) ref).get("id");
        return id instanceof String ? (String) id : null;
    }

    private Instant parseInstant(Object date) {
        if (!(date instanceof String)) return null;
        try {
            return Instant.parse((String) date);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
