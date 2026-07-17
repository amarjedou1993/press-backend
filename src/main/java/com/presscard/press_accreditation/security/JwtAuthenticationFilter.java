package com.presscard.press_accreditation.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs once per request: if a valid Bearer token is present, populate the
 * SecurityContext; otherwise stay silent and let the request continue as
 * anonymous — the entry point will answer 401 on protected routes.
 *
 * Deliberate design choices:
 * - NEVER throw from a filter on a bad token. Throwing produces ugly 500s;
 *   staying anonymous produces clean 401s from one central place.
 * - The user is RELOADED from the database on every request (indexed email
 *   lookup, microseconds). Trade-off accepted on purpose: a disabled or
 *   deleted account loses access immediately, even with a still-valid token.
 *   For an accreditation authority, instant revocation beats one DB hit.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var claims = jwtService.parse(header.substring(7));
            String email = claims.get("email", String.class);

            UserDetails user = userDetailsService.loadUserByUsername(email);
            if (user.isEnabled()) {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (JwtException | AuthenticationException ex) {
            // Invalid/expired token or vanished user: proceed unauthenticated.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
