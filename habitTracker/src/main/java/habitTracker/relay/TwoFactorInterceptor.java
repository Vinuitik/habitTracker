package habitTracker.relay;

import habitTracker.auth.SecurityUtils;
import habitTracker.auth.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Runs after Spring Security (so the user is logged in) on /relay/**: no fresh TOTP, no entry.
 * A valid trusted-device cookie (TrustedDeviceService) for the same, still-enrolled user counts as a fresh check.
 * Pages get a redirect to the TOTP form; /relay/api/** (fetch calls) gets 401 JSON instead.
 */
@Component
@RequiredArgsConstructor
public class TwoFactorInterceptor implements HandlerInterceptor {

    private final TwoFactorService twoFactor;
    private final TrustedDeviceService trusted;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (twoFactor.isVerified(req.getSession())) return true;
        String userId = SecurityUtils.getCurrentUserId();
        if (trusted.accepts(userId, req, res) && twoFactor.currentUser().map(User::isTotpEnabled).orElse(false)) {
            twoFactor.markVerified(req.getSession(), userId);
            req.changeSessionId();   // privilege step-up, same as after a typed code
            return true;
        }
        if (req.getRequestURI().startsWith(req.getContextPath() + "/relay/api/")) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"two-factor verification required\"}");
            return false;
        }
        boolean enrolled = twoFactor.currentUser().map(u -> u.isTotpEnabled()).orElse(false);
        String uri = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        res.sendRedirect((enrolled ? "/2fa/verify" : "/2fa/setup") + "?next=" + URLEncoder.encode(uri, UTF_8));
        return false;
    }
}
