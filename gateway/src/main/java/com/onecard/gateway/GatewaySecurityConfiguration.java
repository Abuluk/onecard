package com.onecard.gateway;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${onecard.security.issuer-uri}") String issuerUri,
            @Value("${onecard.security.audience}") String audience,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator));
        return decoder;
    }

    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter(
            @Value("${onecard.security.roles.user}") String userRole,
            @Value("${onecard.security.roles.admin}") String adminRole,
            @Value("${onecard.security.roles.auditor}") String auditorRole) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, userRole, adminRole, auditorRole));
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    @Bean
    @Order(0)
    SecurityWebFilterChain managementSecurityFilterChain(
            ServerHttpSecurity http,
            @Value("${management.server.port:9081}") int managementPort) {
        return http.securityMatcher(localPortMatcher(managementPort, true))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyExchange().denyAll())
                .build();
    }

    @Bean
    @Order(1)
    SecurityWebFilterChain apiSecurityFilterChain(
            ServerHttpSecurity http,
            @Value("${management.server.port:9081}") int managementPort,
            ReactiveJwtDecoder jwtDecoder,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter) {
        return http.securityMatcher(localPortMatcher(managementPort, false))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/api/payments/callback/**", "/api/payments/callbacks/**").denyAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/account/**", "/api/transactions/**", "/api/payments/**", "/api/notifications/**")
                        .hasAnyRole("ONECARD_USER", "ONECARD_ADMIN", "ONECARD_AUDITOR")
                        .pathMatchers(HttpMethod.POST, "/api/account/**", "/api/transactions/**", "/api/payments/**")
                        .hasAnyRole("ONECARD_USER", "ONECARD_ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/notifications/**").hasRole("ONECARD_ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/clearing/**")
                        .hasAnyRole("ONECARD_ADMIN", "ONECARD_AUDITOR")
                        .pathMatchers(HttpMethod.POST, "/api/clearing/**").hasRole("ONECARD_ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    private static Collection<GrantedAuthority> authorities(
            Jwt jwt, String userRole, String adminRole, String auditorRole) {
        Object realmAccessClaim = jwt.getClaims().get("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> authority(role, userRole, adminRole, auditorRole))
                .filter(Objects::nonNull)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    private static String authority(String role, String userRole, String adminRole, String auditorRole) {
        if (role.equals(userRole)) {
            return "ROLE_ONECARD_USER";
        }
        if (role.equals(adminRole)) {
            return "ROLE_ONECARD_ADMIN";
        }
        if (role.equals(auditorRole)) {
            return "ROLE_ONECARD_AUDITOR";
        }
        return null;
    }

    private static ServerWebExchangeMatcher localPortMatcher(int managementPort, boolean management) {
        return exchange -> {
            InetSocketAddress localAddress = exchange.getRequest().getLocalAddress();
            boolean isManagementPort = localAddress != null && localAddress.getPort() == managementPort;
            return (isManagementPort == management)
                    ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
    }
}
