package habitTracker.relay;

import habitTracker.auth.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Controller
@RequiredArgsConstructor
public class TwoFactorController {

    private final TwoFactorService twoFactor;
    private final TotpService totp;
    private final TrustedDeviceService trusted;

    @GetMapping("/2fa/setup")
    public String setup(@RequestParam(required = false) String next, HttpSession session, Model model) {
        User user = twoFactor.currentUser().orElseThrow();
        if (user.isTotpEnabled()) return "redirect:/2fa/verify?next=" + enc(safeNext(next));
        return renderSetup(user, twoFactor.pendingSecret(session), next, null, model);
    }

    @PostMapping("/2fa/setup")
    public String confirmSetup(@RequestParam String code, @RequestParam(required = false) String next,
                               @RequestParam(defaultValue = "false") boolean trust,
                               HttpServletRequest req, HttpServletResponse res, Model model) {
        HttpSession session = req.getSession();
        TwoFactorService.Result r = twoFactor.confirmSetup(session, code);
        if (r == TwoFactorService.Result.OK) {
            if (trust) trusted.trust(twoFactor.currentUser().orElseThrow().getId(), req, res);
            req.changeSessionId();
            return "redirect:" + safeNext(next);
        }
        if (r == TwoFactorService.Result.NOT_SET_UP) return "redirect:/2fa/setup?next=" + enc(safeNext(next));
        // Same pending secret again, so an already-scanned QR stays valid.
        return renderSetup(twoFactor.currentUser().orElseThrow(), twoFactor.pendingSecret(session), next, message(r), model);
    }

    @GetMapping("/2fa/verify")
    public String verifyPage(@RequestParam(required = false) String next, Model model) {
        User user = twoFactor.currentUser().orElseThrow();
        if (!user.isTotpEnabled()) return "redirect:/2fa/setup?next=" + enc(safeNext(next));
        model.addAttribute("next", safeNext(next));
        model.addAttribute("trustDays", trusted.days());
        return "twofa-verify";
    }

    @PostMapping("/2fa/verify")
    public String verify(@RequestParam String code, @RequestParam(required = false) String next,
                         @RequestParam(defaultValue = "false") boolean trust,
                         HttpServletRequest req, HttpServletResponse res, Model model) {
        TwoFactorService.Result r = twoFactor.verify(req.getSession(), code);
        if (r == TwoFactorService.Result.OK) {
            if (trust) trusted.trust(twoFactor.currentUser().orElseThrow().getId(), req, res);
            req.changeSessionId();
            return "redirect:" + safeNext(next);
        }
        if (r == TwoFactorService.Result.NOT_SET_UP) return "redirect:/2fa/setup?next=" + enc(safeNext(next));
        model.addAttribute("next", safeNext(next));
        model.addAttribute("trustDays", trusted.days());
        model.addAttribute("error", message(r));
        return "twofa-verify";
    }

    // Under /relay/api, so only a session that already passed the gate can see or revoke trusted browsers.
    @GetMapping("/relay/api/trusted-devices")
    @ResponseBody
    public Map<String, Object> trustedDevices() {
        String userId = twoFactor.currentUser().orElseThrow().getId();
        return Map.of("days", trusted.days(), "count", trusted.count(userId));
    }

    @DeleteMapping("/relay/api/trusted-devices")
    @ResponseBody
    public Map<String, Object> forgetTrustedDevices(HttpServletResponse res) {
        return Map.of("removed", trusted.forgetAll(twoFactor.currentUser().orElseThrow().getId(), res));
    }

    private String renderSetup(User user, byte[] secret, String next, String error, Model model) {
        String uri = totp.otpauthUri(user.getEmail(), secret);
        String key = TotpService.base32(secret);
        model.addAttribute("otpauthUri", uri);
        model.addAttribute("qrSvg", totp.qrSvg(uri));
        model.addAttribute("secret", key.replaceAll("(.{4})(?!$)", "$1 "));   // shown, grouped for reading
        model.addAttribute("secretRaw", key);                                  // copied: no spaces, every app accepts it
        model.addAttribute("account", user.getEmail());
        model.addAttribute("trustDays", trusted.days());
        model.addAttribute("next", safeNext(next));
        model.addAttribute("error", error);
        return "twofa-setup";
    }

    private static String message(TwoFactorService.Result r) {
        return r == TwoFactorService.Result.LOCKED
                ? "Too many wrong codes. Try again in 15 minutes."
                : "That code didn't match. Check your authenticator app and try again.";
    }

    /** Only same-site absolute paths; anything else (//host, https://, backslashes) goes to /relay. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\")) return "/relay";
        return next;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, UTF_8);
    }
}
