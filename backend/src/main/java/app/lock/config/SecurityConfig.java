package app.lock.config;

import app.lock.media.storage.LocalStorageController;
import app.lock.memory.MemoryController;
import app.lock.security.AuthRateLimitFilter;
import app.lock.security.RateLimiter;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimiter limiter, LockProperties props) throws Exception {
        return http
                .addFilterBefore(new AuthRateLimitFilter(limiter, props), BearerTokenAuthenticationFilter.class)
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout",
                                "/auth/password-reset", "/auth/password-reset/confirm", "/auth/login/mfa").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Metrics are for operators, not app users: never served on the public port.
                        // Scrape them on the private management port (LOCK_MANAGEMENT_PORT) instead.
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Stand-in for presigned S3 URLs; the controller only exists when lock.storage.type=memory.
                        .requestMatchers(LocalStorageController.PATH + "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Refresh, logout and password reset authenticate with a token in the body. Ignore any
     * Authorization header there, so a stale access token cannot turn them into a 401.
     */
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.equals("/auth/refresh") || path.equals("/auth/logout") || path.startsWith("/auth/password-reset")
                    ? null : standard.resolve(request);
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(LockProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins() == null ? List.of() : props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Accept", "X-MFA-Code"));
        cors.setExposedHeaders(List.of(MemoryController.NEXT_CURSOR, "Retry-After"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtDecoder jwtDecoder(LockProperties props) {
        return NimbusJwtDecoder.withSecretKey(jwtKey(props)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(LockProperties props) {
        return NimbusJwtEncoder.withSecretKey(jwtKey(props)).algorithm(MacAlgorithm.HS256).build();
    }

    private static SecretKey jwtKey(LockProperties props) {
        byte[] bytes = Base64.getDecoder().decode(props.jwt().secret());
        if (bytes.length < 32) {
            throw new IllegalStateException("lock.jwt.secret must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
