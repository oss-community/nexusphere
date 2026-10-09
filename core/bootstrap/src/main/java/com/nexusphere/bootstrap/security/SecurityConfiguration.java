package com.nexusphere.bootstrap.security;

import com.nexusphere.identity.contract.CredentialVerifier;
import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.time.TimeProvider;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TokenProperties.class, OperatorProperties.class})
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, JsonMapper json) throws Exception {
        ApiErrorWriter errors = new ApiErrorWriter(json);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token", "/api/v1/auth/operator-token")
                        .permitAll()
                        .requestMatchers("/api/v1/platform", "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus", "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            if (!(exception instanceof OAuth2AuthenticationException oauth)) {
                                errors.write(request, response, HttpStatus.UNAUTHORIZED.value(),
                                        "AUTHENTICATION_REQUIRED", ErrorCategory.AUTHENTICATION_ERROR,
                                        "A bearer token is required");
                                return;
                            }
                            String message = oauth.getError().getDescription() != null
                                    ? oauth.getError().getDescription() : "The bearer token is not valid";
                            errors.write(request, response, HttpStatus.UNAUTHORIZED.value(), "INVALID_TOKEN",
                                    ErrorCategory.AUTHENTICATION_ERROR, message);
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(request, response, HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                                        ErrorCategory.AUTHORIZATION_ERROR, "Access is denied")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenProperties properties, IdentityDirectory identities, CredentialVerifier credentials) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new ActiveIdentityValidator(identities, credentials)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(TokenProperties properties) {
        return NimbusJwtEncoder.withSecretKey(key(properties)).build();
    }

    @Bean
    LocalTokenIssuer localTokenIssuer(JwtEncoder encoder, TokenProperties properties, TimeProvider time) {
        return new LocalTokenIssuer(encoder, properties, time);
    }

    private static SecretKey key(TokenProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
