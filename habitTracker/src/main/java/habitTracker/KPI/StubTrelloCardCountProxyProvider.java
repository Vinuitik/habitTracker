package habitTracker.KPI;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * M1 placeholder for ProxyType.TRELLO_CARD_COUNT: makes no real Trello call (that's a later
 * milestone) and always returns a fixed value, so the registry -> provider -> KPIService write
 * path is fully exercisable end-to-end without Trello credentials. Swap this bean for a real
 * Trello-calling ProxyProvider of the same type when that milestone lands — ProxyProviderRegistry
 * only ever sees one provider per ProxyType, so the replacement is a drop-in.
 */
@Component
public class StubTrelloCardCountProxyProvider implements ProxyProvider {

    private static final double FIXED_VALUE = 1.0;

    @Override
    public ProxyType getType() {
        return ProxyType.TRELLO_CARD_COUNT;
    }

    @Override
    public Optional<Double> fetchValue(KPI kpi, LocalDate date) {
        return Optional.of(FIXED_VALUE);
    }
}
