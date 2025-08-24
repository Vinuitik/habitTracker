package habitTracker.KPI;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class KPICollectionNameUtil {
    
    private static final String COLLECTION_PREFIX = "kpi_data_";
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9_]");
    private static final int MAX_COLLECTION_NAME_LENGTH = 100; // MongoDB limit is 127, leaving some buffer
    
    /**
     * Converts a KPI name to a valid MongoDB collection name
     * Example: "Daily Steps Count" -> "kpi_data_daily_steps_count"
     */
    public String toCollectionName(String kpiName) {
        if (kpiName == null || kpiName.trim().isEmpty()) {
            throw new IllegalArgumentException("KPI name cannot be null or empty");
        }
        
        String sanitized = kpiName.trim()
                .toLowerCase()
                .replaceAll("\\s+", "_") // Replace spaces with underscores
                .replaceAll(INVALID_CHARS.pattern(), "_") // Replace invalid chars with underscores
                .replaceAll("_{2,}", "_") // Replace multiple underscores with single
                .replaceAll("^_|_$", ""); // Remove leading/trailing underscores
        
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("KPI name contains no valid characters");
        }
        
        String collectionName = COLLECTION_PREFIX + sanitized;
        
        // Truncate if too long
        if (collectionName.length() > MAX_COLLECTION_NAME_LENGTH) {
            collectionName = collectionName.substring(0, MAX_COLLECTION_NAME_LENGTH);
            // Ensure it doesn't end with underscore after truncation
            collectionName = collectionName.replaceAll("_$", "");
        }
        
        return collectionName;
    }
    
    /**
     * Extracts the original KPI name from a collection name
     * Example: "kpi_data_daily_steps_count" -> "daily_steps_count"
     * Note: This won't perfectly reverse the sanitization, but gives the sanitized version
     */
    public String fromCollectionName(String collectionName) {
        if (collectionName == null || !collectionName.startsWith(COLLECTION_PREFIX)) {
            throw new IllegalArgumentException("Invalid collection name format");
        }
        
        return collectionName.substring(COLLECTION_PREFIX.length());
    }
    
    /**
     * Validates if a collection name is a valid KPI data collection
     */
    public boolean isKPIDataCollection(String collectionName) {
        return collectionName != null && collectionName.startsWith(COLLECTION_PREFIX);
    }
}
