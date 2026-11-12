package com.onecard.gateway;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayProductionRoutesTest {

    private static final String FIRST_ROUTE_ID = "spring.cloud.gateway.server.webflux.routes[0].id";

    @Test
    void productionConfigurationHasNoBusinessRoutesBeforeApiMatrixApproval() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> base = loader.load("base", new ClassPathResource("application.yml"));
        List<PropertySource<?>> production = loader.load("production", new ClassPathResource("application-prod.yml"));
        List<PropertySource<?>> development = loader.load("development", new ClassPathResource("application-dev.yml"));

        assertThat(base.getFirst().getProperty(FIRST_ROUTE_ID)).isNull();
        assertThat(production.getFirst().getProperty(FIRST_ROUTE_ID)).isNull();
        assertThat(development.getFirst().getProperty(FIRST_ROUTE_ID)).isNull();
    }

    @Test
    void testRouteAuthorizationCannotBeEnabledOutsideTestProfiles() {
        assertThat(GatewaySecurityConfiguration.testRouteAuthorizationEnabled(environment("dev"), true)).isFalse();
        assertThat(GatewaySecurityConfiguration.testRouteAuthorizationEnabled(environment("prod"), true)).isFalse();
        assertThat(GatewaySecurityConfiguration.testRouteAuthorizationEnabled(environment("test"), true)).isTrue();
        assertThat(GatewaySecurityConfiguration.testRouteAuthorizationEnabled(environment("performance"), true))
                .isTrue();
    }

    @Test
    void performanceProfileConfigurationLoadsAndEnablesTestRoutes() throws Exception {
        List<PropertySource<?>> performance = new YamlPropertySourceLoader()
                .load("performance", new ClassPathResource("application-performance.yml"));

        assertThat(performance.getFirst().getProperty("onecard.security.test-routes.enabled")).isEqualTo(true);
        assertThat(performance.getFirst().getProperty(FIRST_ROUTE_ID)).isEqualTo("performance-stub");
    }

    private static MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
