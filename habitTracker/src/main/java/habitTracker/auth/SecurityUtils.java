package habitTracker.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);
    private SecurityUtils() {}

    public static String getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) { log.warn("[SecurityUtils] auth=null"); return null; }
        if (!auth.isAuthenticated()) { log.warn("[SecurityUtils] not authenticated, class={}", auth.getClass().getSimpleName()); return null; }
        log.info("[SecurityUtils] auth class={} principal class={}", auth.getClass().getSimpleName(), auth.getPrincipal().getClass().getSimpleName());
        if (auth.getPrincipal() instanceof UserPrincipal up) return up.getId();
        log.warn("[SecurityUtils] principal is not UserPrincipal, returning null");
        return null;
    }
}
