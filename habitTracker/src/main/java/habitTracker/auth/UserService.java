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

    public UserPrincipal register(String email, String rawPassword, String name) {
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
