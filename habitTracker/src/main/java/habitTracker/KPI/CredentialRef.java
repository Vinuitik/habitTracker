package habitTracker.KPI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A KPI API credential by reference: name + host only (runner-only-kpis.md). The value lives on the user's runner
 * (GET /v1/credentials never returns it) and never reaches the hub outside the browser's PUT through the proxy.
 * Stored in declaredCapabilities.credentials as "name@host" (neither part can contain '@').
 */
public record CredentialRef(String name, String host) {

    /** Same as the runner's credential name rule (HUB.md "KPI credentials"). */
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    /** A bare hostname: labels of [a-z0-9-], at least one dot, no scheme/port/path. The runner does the full public-host check. */
    static final Pattern HOST = Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    public CredentialRef {
        name = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        host = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
    }

    boolean valid() {
        return NAME.matcher(name).matches() && HOST.matcher(host).matches();
    }

    String stored() {
        return name + "@" + host;
    }

    static CredentialRef parse(String stored) {
        int at = stored == null ? -1 : stored.indexOf('@');
        return at < 0 ? new CredentialRef(stored, "") : new CredentialRef(stored.substring(0, at), stored.substring(at + 1));
    }

    /** name → host, first wins. */
    static Map<String, String> byName(Collection<CredentialRef> refs) {
        Map<String, String> m = new LinkedHashMap<>();
        if (refs != null) refs.forEach(r -> m.putIfAbsent(r.name(), r.host()));
        return m;
    }

    static List<String> storedList(Collection<CredentialRef> refs) {
        List<String> out = new ArrayList<>();
        refs.forEach(r -> out.add(r.stored()));
        return out;
    }
}
