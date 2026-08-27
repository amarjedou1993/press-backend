////package com.presscard.press_accreditation.security;
////
////import com.presscard.press_accreditation.config.AppProperties;
////import org.springframework.boot.web.servlet.FilterRegistrationBean;
////import org.springframework.context.annotation.Bean;
////import org.springframework.context.annotation.Configuration;
////import org.springframework.http.HttpHeaders;
////import org.springframework.http.HttpMethod;
////import org.springframework.security.authentication.AuthenticationManager;
////import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
////import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
////import org.springframework.security.config.annotation.web.builders.HttpSecurity;
////import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
////import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
////import org.springframework.security.config.http.SessionCreationPolicy;
////import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
////import org.springframework.security.crypto.password.PasswordEncoder;
////import org.springframework.security.web.SecurityFilterChain;
////import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
////import org.springframework.web.cors.CorsConfiguration;
////import org.springframework.web.cors.CorsConfigurationSource;
////import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
////
////import java.util.List;
////
////import static org.springframework.security.config.Customizer.withDefaults;
//
///**
// * The security posture of the whole API.
// *
// * Whitelist style: everything requires authentication unless explicitly
// * opened, so a forgotten rule fails CLOSED. That is exactly what happened to
// * /api/public/** — it was created for the public pages but never whitelisted,
// * so it answered 401 and the pages rendered empty. Fixed below.
// *
// * Note the GET-only restriction on the public namespace: read access is open,
// * but nothing there can ever be written to anonymously, whatever a future
// * controller might add.
// */
////@Configuration
////@EnableWebSecurity
////@EnableMethodSecurity
////public class SecurityConfig {
////
////    @Bean
////    SecurityFilterChain securityFilterChain(HttpSecurity http,
////                                            JwtAuthenticationFilter jwtFilter,
////                                            RestSecurityHandlers.RestAuthenticationEntryPoint entryPoint,
////                                            RestSecurityHandlers.RestAccessDeniedHandler deniedHandler)
////            throws Exception {
////        http
////                .cors(withDefaults())
////                .csrf(AbstractHttpConfigurer::disable)
////                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
////                .exceptionHandling(e -> e
////                        .authenticationEntryPoint(entryPoint)
////                        .accessDeniedHandler(deniedHandler))
////                .authorizeHttpRequests(auth -> auth
////                        // ── open: authentication endpoints ──
////                        .requestMatchers("/api/auth/**").permitAll()
////                        // ── open: the public read-only namespace (sessions,
////                        //    categories, and later the accredited registry) ──
////                        .requestMatchers(HttpMethod.GET, "/api/public/**", "/api/public/verify/{token}","/api/public/verify/{token}/photo")
////                        .permitAll()
////                        // ── open: ops + docs ──
////                        .requestMatchers(
////                                "/actuator/health/**",
////                                "/api-docs/**",
////                                "/swagger-ui/**",
////                                "/swagger-ui.html").permitAll()
////                        // ── role-gated ──
////                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
////                        // ── fail closed ──
////                        .anyRequest().authenticated())
////                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
////
////        return http.build();
////    }
////
////    /** The JWT filter belongs to the security chain ONLY — a @Component filter
////     *  added to the chain is also auto-registered by Boot and would run twice. */
////    @Bean
////    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
////        FilterRegistrationBean<JwtAuthenticationFilter> registration =
////                new FilterRegistrationBean<>(filter);
////        registration.setEnabled(false);
////        return registration;
////    }
////
////    @Bean
////    PasswordEncoder passwordEncoder() {
////        return new BCryptPasswordEncoder();
////    }
////
////    @Bean
////    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
////        return config.getAuthenticationManager();
////    }
////
////    @Bean
////    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
////        CorsConfiguration cfg = new CorsConfiguration();
////        cfg.setAllowedOrigins(props.cors().allowedOrigins());
////        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
////        cfg.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
////        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
////        source.registerCorsConfiguration("/api/**", cfg);
////        return source;
////    }
////}
//
//package com.presscard.press_accreditation.security;
//
//import com.presscard.press_accreditation.config.AppProperties;
//import org.springframework.boot.web.servlet.FilterRegistrationBean;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpMethod;
//import org.springframework.security.authentication.AuthenticationManager;
//import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
//import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.security.web.SecurityFilterChain;
//import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
//import org.springframework.web.cors.CorsConfiguration;
//import org.springframework.web.cors.CorsConfigurationSource;
//import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
//
//import java.util.List;
//
//import static org.springframework.security.config.Customizer.withDefaults;
//
//
//@Configuration
//@EnableWebSecurity
//@EnableMethodSecurity
//public class SecurityConfig {
//
//    @Bean
//    SecurityFilterChain securityFilterChain(HttpSecurity http,
//                                            JwtAuthenticationFilter jwtFilter,
//                                            RestSecurityHandlers.RestAuthenticationEntryPoint entryPoint,
//                                            RestSecurityHandlers.RestAccessDeniedHandler deniedHandler)
//            throws Exception {
//        http
//                .cors(withDefaults())
//                .csrf(AbstractHttpConfigurer::disable)
//                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                .exceptionHandling(e -> e
//                        .authenticationEntryPoint(entryPoint)
//                        .accessDeniedHandler(deniedHandler))
//                .authorizeHttpRequests(auth -> auth
//                        /* ── open: authentication endpoints ──
//                           ⚠️ THIS IS A WHOLE NAMESPACE, and it has to be:
//                           login, registration, forgot-password and a
//                           verification link clicked from an inbox all arrive
//                           without a session.
//
//                           Which means any AUTHENTICATED endpoint placed under
//                           /api/auth — changing one's password, requesting an
//                           e-mail change — is public unless it carries its own
//                           @PreAuthorize. Two already do. Anything added there
//                           later must too. */
//                        .requestMatchers("/api/auth/**").permitAll()
//
//                        // ── open: the public read-only namespace (sessions,
//                        //    categories, verification, the accredited registry)
//                        .requestMatchers(HttpMethod.GET,
//                                "/api/public/**",
//                                "/api/public/verify/{token}",
//                                "/api/public/verify/{token}/photo")
//                        .permitAll()
//
//                        // ── open: ops + docs ──
//                        .requestMatchers(
//                                "/actuator/health/**",
//                                "/api-docs/**",
//                                "/swagger-ui/**",
//                                "/swagger-ui.html").permitAll()
//
//                        // ── role-gated ──
//                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
//
//                        // ── fail closed ──
//                        .anyRequest().authenticated())
//                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
//
//        return http.build();
//    }
//
//    /** The JWT filter belongs to the security chain ONLY — a @Component filter
//     *  added to the chain is also auto-registered by Boot and would run twice. */
//    @Bean
//    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
//        FilterRegistrationBean<JwtAuthenticationFilter> registration =
//                new FilterRegistrationBean<>(filter);
//        registration.setEnabled(false);
//        return registration;
//    }
//
//    @Bean
//    PasswordEncoder passwordEncoder() {
//        return new BCryptPasswordEncoder();
//    }
//
//    @Bean
//    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
//        return config.getAuthenticationManager();
//    }
//
//    @Bean
//    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
//        CorsConfiguration cfg = new CorsConfiguration();
//        cfg.setAllowedOrigins(props.cors().allowedOrigins());
//        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
//        cfg.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
//
//        /*
//         * ⚠️ WITHOUT THIS, EVERY DOWNLOAD IS MIS-NAMED — SILENTLY.
//         *
//         * A browser lets JavaScript read only six response headers by default,
//         * and Content-Disposition is not among them. So
//         *
//         *     res.headers.get("content-disposition")
//         *
//         * returns null on every cross-origin request, and each download falls
//         * back to a generic filename instead of the one the server composed
//         * with the card number, the print layout or the session date.
//         *
//         * Nothing fails. Nothing is logged. The files simply arrive named
//         * plausibly enough that nobody questions it — until three exports of
//         * different sessions sit in one folder, indistinguishable.
//         *
//         * The two X-Archive counts are here for the same reason: the archive's
//         * body is a binary file, so its "3 of 40 had no photograph" has
//         * nowhere else to travel.
//         */
//        cfg.setExposedHeaders(List.of(
//                HttpHeaders.CONTENT_DISPOSITION,
//                "X-Archive-Included",
//                "X-Archive-Skipped"));
//
//        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
//        source.registerCorsConfiguration("/api/**", cfg);
//        return source;
//    }
//}

package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter,
                                            RestSecurityHandlers.RestAuthenticationEntryPoint entryPoint,
                                            RestSecurityHandlers.RestAccessDeniedHandler deniedHandler)
            throws Exception {
        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(auth -> auth
                        /* ── open: authentication endpoints ──
                           ⚠️ THIS IS A WHOLE NAMESPACE, and it has to be:
                           login, registration, forgot-password and a
                           verification link clicked from an inbox all arrive
                           without a session.

                           Which means any AUTHENTICATED endpoint placed under
                           /api/auth — changing one's password, requesting an
                           e-mail change — is public unless it carries its own
                           @PreAuthorize. Two already do. Anything added there
                           later must too. */
                        .requestMatchers("/api/auth/**").permitAll()

                        // ── open: the public read-only namespace (sessions,
                        //    categories, verification, the accredited registry)
                        .requestMatchers(HttpMethod.GET,
                                "/api/public/**",
                                "/api/public/verify/{token}",
                                "/api/public/verify/{token}/photo")
                        .permitAll()

                        // ── open: ops + docs ──
                        .requestMatchers(
                                "/actuator/health/**",
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()

                        /* ── role-gated ──
                           ⚠️ ORDER MATTERS. Spring takes the FIRST matcher
                           that matches, so the narrower namespace comes
                           first and anyRequest() comes last. */
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")

                        /*
                         * ⚠️ hasAnyRole, AND DELIBERATELY SO.
                         *
                         * Spring's roles are not hierarchical: SUPER_ADMIN
                         * inherits nothing. Without the second value an
                         * administrator gets a 403 on the producer's screen —
                         * and so can neither help when something goes wrong
                         * nor cover an absence.
                         *
                         * The converse is NOT true. /api/admin/** stays
                         * SUPER_ADMIN alone: a printer does not see the
                         * Ministry's side.
                         */
                        .requestMatchers("/api/printer/**")
                        .hasAnyRole("PRINTER", "SUPER_ADMIN")

                        // ── fail closed ──
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** The JWT filter belongs to the security chain ONLY — a @Component filter
     *  added to the chain is also auto-registered by Boot and would run twice. */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));

        /*
         * ⚠️ WITHOUT THIS, EVERY DOWNLOAD IS MIS-NAMED — SILENTLY.
         *
         * A browser lets JavaScript read only six response headers by default,
         * and Content-Disposition is not among them. So
         *
         *     res.headers.get("content-disposition")
         *
         * returns null on every cross-origin request, and each download falls
         * back to a generic filename instead of the one the server composed
         * with the card number, the print layout or the session date.
         *
         * Nothing fails. Nothing is logged. The files simply arrive named
         * plausibly enough that nobody questions it — until three exports of
         * different sessions sit in one folder, indistinguishable.
         *
         * The two X-Archive counts are here for the same reason: the archive's
         * body is a binary file, so its "3 of 40 had no photograph" has
         * nowhere else to travel.
         */
        cfg.setExposedHeaders(List.of(
                HttpHeaders.CONTENT_DISPOSITION,
                "X-Archive-Included",
                "X-Archive-Skipped"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}