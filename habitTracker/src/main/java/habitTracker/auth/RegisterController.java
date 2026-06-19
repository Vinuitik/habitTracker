package habitTracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class RegisterController {

    private static final Logger log = LoggerFactory.getLogger(RegisterController.class);
    private final UserService userService;

    @GetMapping("/auth/me")
    @ResponseBody
    public ResponseEntity<?> me() {
        String userId = SecurityUtils.getCurrentUserId();
        log.info("[/auth/me] userId={}", userId);
        if (userId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           @RequestParam(required = false) String name,
                           Model model,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        try {
            UserPrincipal principal = userService.register(email, password, name != null ? name : email);
            // Auto-login after successful registration
            var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.getContext(), request, response);
            // Land on the authenticated home (habit list), matching formLogin's defaultSuccessUrl.
            // "/" forwards to landing.html (the signed-out marketing page), which is why sign-up
            // appeared to "not log you in" — you were auto-authenticated but dropped on the landing page.
            return "redirect:/today";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "register";
        }
    }
}
