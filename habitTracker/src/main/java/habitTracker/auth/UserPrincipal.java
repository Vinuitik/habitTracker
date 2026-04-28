package habitTracker.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serial;
import java.util.*;

public class UserPrincipal implements UserDetails, OidcUser {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String email;
    private final String password;
    private final Map<String, Object> attributes;
    private OidcIdToken oidcIdToken;
    private OidcUserInfo oidcUserInfo;

    private UserPrincipal(String id, String email, String password, Map<String, Object> attributes) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.attributes = attributes != null ? Collections.unmodifiableMap(attributes) : Collections.emptyMap();
    }

    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), Collections.emptyMap());
    }

    public static UserPrincipal fromOAuth2(User user, OidcUser oidcUser) {
        UserPrincipal p = new UserPrincipal(user.getId(), user.getEmail(), null,
                oidcUser != null ? new HashMap<>(oidcUser.getAttributes()) : Collections.emptyMap());
        if (oidcUser != null) {
            p.oidcIdToken = oidcUser.getIdToken();
            p.oidcUserInfo = oidcUser.getUserInfo();
        }
        return p;
    }

    public String getId() { return id; }

    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return password; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }

    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public String getName() { return email; }
    @Override public Map<String, Object> getClaims() { return attributes; }
    @Override public OidcUserInfo getUserInfo() { return oidcUserInfo; }
    @Override public OidcIdToken getIdToken() { return oidcIdToken; }
}
