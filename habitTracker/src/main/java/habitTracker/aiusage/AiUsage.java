package habitTracker.aiusage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One finished runner job's token usage. Unique per (userId, jobId): see AiUsageService.ensureIndexes(). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_usage")
public class AiUsage {

    public static final String MAP = "map";
    public static final String PLAN = "plan";
    public static final String KPI_PROPOSE = "kpi_propose";
    public static final String KPI_GENERATE = "kpi_generate";
    public static final String KPI_REPAIR = "kpi_repair";

    @Id
    private String id;
    private String userId;
    private String kind;        // map | plan | kpi_propose | kpi_generate | kpi_repair
    private String projectId;   // map / plan only
    private String kpiName;     // kpi_* only
    private long inputTokens;   // includes cache reads/writes (runner reports it that way)
    private long outputTokens;
    private Instant at;
    private String jobId;
}
