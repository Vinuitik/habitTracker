package habitTracker.KPI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TrelloCardCountProvider (M2), against a MOCKED RestTemplate — no real Trello
 * call, no network. Covers the fixtures called out in the M2 spec: empty list, cards with no
 * move date, cards moved multiple times (same-day dedupe), cards moved on a different day
 * (excluded), plus the credential/config guard rails that must degrade to Optional.empty()
 * rather than throw.
 */
@ExtendWith(MockitoExtension.class)
class TrelloCardCountProviderTest {

    @Mock TrelloCredentialRepository credentialRepository;
    @Mock RestTemplate restTemplate;

    private static final String USER_ID = "user-1";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 30);

    private TrelloCardCountProvider provider() {
        return new TrelloCardCountProvider(credentialRepository, restTemplate, "https://api.trello.test");
    }

    private KPI kpi(Map<String, String> proxyConfig) {
        return KPI.builder()
                .id("kpi-1")
                .userId(USER_ID)
                .name("Cards Closed")
                .proxyType(ProxyType.TRELLO_CARD_COUNT)
                .proxyConfig(proxyConfig)
                .build();
    }

    private TrelloCredential credential() {
        return TrelloCredential.builder().userId(USER_ID).apiKey("key-1").token("token-1").build();
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(List<Map<String, Object>> actions) {
        ResponseEntity<List<Map<String, Object>>> response = ResponseEntity.ok(actions);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(response);
    }

    private Map<String, Object> updateCardAction(String cardId, String listAfterId, String isoDate) {
        return Map.of(
                "date", isoDate,
                "data", Map.of(
                        "card", Map.of("id", cardId),
                        "listAfter", Map.of("id", listAfterId)
                )
        );
    }

    // --- credential / config guard rails -----------------------------------------------------

    @Test
    void missingCredentials_returnsEmpty_doesNotCallTrello() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void blankToken_returnsEmpty_doesNotCallTrello() {
        TrelloCredential bad = TrelloCredential.builder().userId(USER_ID).apiKey("key-1").token("  ").build();
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bad));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void blankApiKey_returnsEmpty_doesNotCallTrello() {
        TrelloCredential bad = TrelloCredential.builder().userId(USER_ID).apiKey("").token("token-1").build();
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bad));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void missingListIdInProxyConfig_returnsEmpty_doesNotCallTrello() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("boardId", "board-1")), TARGET_DATE);

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void nullProxyConfig_returnsEmpty_doesNotCallTrello() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));

        Optional<Double> result = provider().fetchValue(kpi(null), TARGET_DATE);

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    // --- fixtures: empty / no-op ---------------------------------------------------------------

    @Test
    void emptyActionsList_returnsZero() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of());

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(0.0), result);
    }

    @Test
    void cardsWithNoMoveIntoConfiguredList_returnsZero() {
        // Action present, but it moved a card OUT of our list (listAfter points elsewhere) —
        // must not be counted as a card moved IN.
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of(updateCardAction("card-1", "some-other-list", "2026-08-30T10:00:00.000Z")));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(0.0), result);
    }

    @Test
    void actionWithNoDataOrListAfter_isIgnoredNotCrashed() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        Map<String, Object> malformed = Map.of("date", "2026-08-30T10:00:00.000Z", "type", "createCard");
        stubResponse(List.of(malformed));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(0.0), result);
    }

    // --- fixtures: real moves --------------------------------------------------------------

    @Test
    void singleCardMovedIn_countsOne() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of(updateCardAction("card-1", "list-1", "2026-08-30T10:00:00.000Z")));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(1.0), result);
    }

    @Test
    void cardMovedMultipleTimesIntoListOnSameDay_dedupedToOne() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of(
                updateCardAction("card-1", "list-1", "2026-08-30T09:00:00.000Z"),
                updateCardAction("card-1", "list-1", "2026-08-30T15:00:00.000Z") // same card, moved in again later
        ));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(1.0), result);
    }

    @Test
    void multipleDistinctCardsMovedIn_countsEach() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of(
                updateCardAction("card-1", "list-1", "2026-08-30T09:00:00.000Z"),
                updateCardAction("card-2", "list-1", "2026-08-30T11:00:00.000Z")
        ));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(2.0), result);
    }

    @Test
    void cardMovedOnDifferentDay_notCounted() {
        // Defense in depth: even though since/before are sent to Trello, a defensive in-code
        // date check also excludes anything outside the requested day.
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of(
                updateCardAction("card-1", "list-1", "2026-08-29T23:59:59.000Z"), // day before
                updateCardAction("card-2", "list-1", "2026-08-31T00:00:00.000Z")  // day after (exclusive bound)
        ));

        Optional<Double> result = provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE);

        assertEquals(Optional.of(0.0), result);
    }

    // --- error containment -------------------------------------------------------------------

    @Test
    void trelloHttpCallThrows_returnsEmpty_doesNotPropagate() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        Optional<Double> result = assertDoesNotThrow(() ->
                provider().fetchValue(kpi(Map.of("listId", "list-1")), TARGET_DATE));

        assertTrue(result.isEmpty());
    }

    // --- request shape -----------------------------------------------------------------------

    @Test
    void requestUrl_scopesToConfiguredListAndIncludesAuthParams() {
        when(credentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential()));
        stubResponse(List.of());

        provider().fetchValue(kpi(Map.of("listId", "list-42")), TARGET_DATE);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue().toString();

        assertTrue(uri.contains("/1/lists/list-42/actions"), uri);
        assertTrue(uri.contains("key=key-1"), uri);
        assertTrue(uri.contains("token=token-1"), uri);
        assertTrue(uri.contains("filter=updateCard"), uri);
    }

    @Test
    void getType_returnsTrelloCardCount() {
        assertEquals(ProxyType.TRELLO_CARD_COUNT, provider().getType());
    }
}
