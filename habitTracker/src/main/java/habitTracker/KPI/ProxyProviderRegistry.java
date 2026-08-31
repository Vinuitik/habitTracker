package habitTracker.KPI;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Collects every ProxyProvider bean in the context and keys it by the ProxyType it declares
 * (ProxyProvider.getType()), so the nightly proxy-fill step can resolve "this KPI's proxyType"
 * -> "the provider that knows how to fetch it" without a switch statement that has to be
 * updated every time a new provider ships.
 */
@Component
public class ProxyProviderRegistry {

    private final Map<ProxyType, ProxyProvider> providersByType;

    public ProxyProviderRegistry(List<ProxyProvider> providers) {
        this.providersByType = providers.stream()
                .collect(Collectors.toMap(ProxyProvider::getType, p -> p));
    }

    public Optional<ProxyProvider> resolve(ProxyType type) {
        return Optional.ofNullable(providersByType.get(type));
    }
}
