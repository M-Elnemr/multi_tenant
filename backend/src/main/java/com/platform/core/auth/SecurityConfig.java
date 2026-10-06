package com.platform.core.auth;

import com.platform.core.rbac.RbacService;
import com.platform.core.tenant.TenantResolutionFilter;
import com.platform.core.user.MembershipRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    // The filters below are part of the security chain; stop Boot also registering them as plain servlet filters.
    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantFilterRegistration(TenantResolutionFilter f) {
        FilterRegistrationBean<TenantResolutionFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, TenantResolutionFilter tenantFilter, JwtService jwt,
                              MembershipRepository memberships, RbacService rbac) throws Exception {
        http.csrf(c -> c.disable())
            .cors(Customizer -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/me").authenticated()
                .requestMatchers("/api/v1/auth/**", "/api/v1/onboarding/**", "/api/v1/tenant/context",
                        "/api/v1/tenant/public", "/internal/domains/allowed",
                        "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"status\":401,\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"status\":403,\"code\":\"FORBIDDEN\",\"message\":\"You do not have permission to do this\"}");
                }))
            .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new JwtAuthFilter(jwt, memberships, rbac), TenantResolutionFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        List<String> list = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (list.contains("*")) c.setAllowedOriginPatterns(List.of("*")); else c.setAllowedOrigins(list);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Forwarded-Host"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
