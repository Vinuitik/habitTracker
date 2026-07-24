package habitTracker.KPI;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class KPICollectionNameUtil {
    
    private static final String COLLECTION_PREFIX = "kpi_data_";
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9_]");

    /**
     * Converts a KPI's own id (globally unique) to its MongoDB data collection name.
     * Keyed by id rather than by the user-entered name: two KPIs are allowed to share a
     * name across different users (unique index is {name, userId}), so naming the
     * collection after the name would let them collide into the same collection.
     */
    public String toCollectionName(String kpiId) {
        if (kpiId == null || kpiId.trim().isEmpty()) {
            throw new IllegalArgumentException("KPI id cannot be null or empty");
        }
        String sanitized = INVALID_CHARS.matcher(kpiId.trim()).replaceAll("_");
        return COLLECTION_PREFIX + sanitized;
    }
}
