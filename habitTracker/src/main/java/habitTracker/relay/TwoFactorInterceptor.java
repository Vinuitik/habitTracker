package habitTracker.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Runs after Spring Security (so the user is logged in) on /relay/**: no fresh TOTP, no entry.
 * Pages get a redirect to the TOTP form; /relay/api/** (fetch calls) gets 401 JSON instead.
 */
@Component
@RequiredArgsConstructor
public class TwoFactorInterceptor implements HandlerInterceptor {

    private final TwoFactorService twoFactor;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (twoFactor.isVerified(req.getSession())) return true;
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
