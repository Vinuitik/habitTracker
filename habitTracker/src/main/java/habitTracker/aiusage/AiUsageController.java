package habitTracker.aiusage;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /api/ai-usage?days=30 → current user's runner-job token usage (AiUsageService.summary). */
@RestController
@RequestMapping("/api/ai-usage")
@RequiredArgsConstructor
public class AiUsageController {

    private final AiUsageService service;

    @GetMapping
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        return service.summary(SecurityUtils.getCurrentUserId(), days);
    }
}
