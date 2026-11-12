package com.onecard.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import com.onecard.common.security.ServletSecurityErrorHandlers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AccountServiceSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${onecard.security.issuer-uri}") String issuerUri,
            @Value("${onecard.security.audience}") String audience,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain accountServiceSecurityFilterChain(
            HttpSecurity http,
            JsonMapper jsonMapper,
            @Value("${onecard.security.required-scope}") String requiredScope,
            @Value("${onecard.dev.config-probe.enabled:false}") boolean configProbeEnabled,
            Environment environment) throws Exception {
        var authenticationEntryPoint = ServletSecurityErrorHandlers.authenticationEntryPoint(
                "onecard-account-service", jsonMapper);
        var accessDeniedHandler = ServletSecurityErrorHandlers.accessDeniedHandler(
                "onecard-account-service", jsonMapper);
        boolean devConfigProbeEnabled = configProbeEnabled && environment.acceptsProfiles(Profiles.of("dev"));
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET,
                            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                            .permitAll();
                    authorize.requestMatchers("/actuator/**").denyAll();
                    if (devConfigProbeEnabled) {
                        authorize.requestMatchers(HttpMethod.GET, "/internal/dev/config-probe").permitAll();
                    }
                    authorize.anyRequest().hasAuthority("SCOPE_" + requiredScope);
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .build();
    }
}
