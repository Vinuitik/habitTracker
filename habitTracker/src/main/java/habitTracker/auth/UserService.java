package habitTracker.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Basic RFC-5322-ish email shape; deliberately permissive, just rejects obvious garbage.
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    public UserPrincipal register(String email, String rawPassword, String name) {
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        email = email.trim();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .name(name)
                .build();
        return UserPrincipal.fromUser(userRepository.save(user));
    }

    public UserPrincipal findOrCreateOAuthUser(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        String googleId = oidcUser.getSubject();

        // Case 4: match by googleId — email may have changed
        return userRepository.findByGoogleId(googleId)
                .map(u -> {
                    if (!email.equals(u.getEmail())) {
                        u.setEmail(email);
                        userRepository.save(u);
                    }
                    return UserPrincipal.fromOAuth2(u, oidcUser);
                })
                // Case 3: email matches local account — add googleId
                .or(() -> userRepository.findByEmail(email).map(u -> {
                    u.setGoogleId(googleId);
                    userRepository.save(u);
                    return UserPrincipal.fromOAuth2(u, oidcUser);
                }))
                // Case 1: new user
                .orElseGet(() -> {
                    User u = User.builder()
                            .email(email)
                            .googleId(googleId)
                            .name(oidcUser.getFullName())
                            .build();
                    return UserPrincipal.fromOAuth2(userRepository.save(u), oidcUser);
                });
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(UserPrincipal::fromUser)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
