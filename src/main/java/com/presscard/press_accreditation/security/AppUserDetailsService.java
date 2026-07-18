package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges our User entity to Spring Security.
 *
 * The authority is "ROLE_" + role name — that prefix is what makes
 * hasRole("SUPER_ADMIN") in SecurityConfig work.
 *
 * enabled=false users are surfaced as disabled UserDetails: the
 * AuthenticationManager then rejects their login, and the JWT filter
 * (which reloads the user per request) rejects their existing tokens too —
 * disabling an account is instant, no token blacklist needed.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user: " + email));

        return User.builder()
                .username(user.getEmail())
                // Khidmaty-only users (V2) have no local password; empty hash can never match
                .password(user.getPasswordHash() != null ? user.getPasswordHash() : "")
                .disabled(!user.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
