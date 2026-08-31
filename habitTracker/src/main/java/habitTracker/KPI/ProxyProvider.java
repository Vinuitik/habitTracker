package habitTracker.KPI;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Resolves an automatic value for a KPI on a given date from some external or local signal
 * (Trello card count, a future capability hook, etc). Implementations are Spring beans looked
 * up by ProxyType via ProxyProviderRegistry, which collects every ProxyProvider bean and keys
 * it by getType(). Returning Optional.empty() means "no value available for this date" — the
 * nightly proxy-fill step (habitTracker.updater.KPIProxyFillService) treats that as a skip, not
 * an error, and simply leaves the day unfilled.
 */
public interface ProxyProvider {

    ProxyType getType();

    Optional<Double> fetchValue(KPI kpi, LocalDate date);
}
