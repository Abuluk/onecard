package com.onecard.common;

import com.onecard.common.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.servlet.Filter;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class OneCardCommonAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    Filter traceparentResponseFilter(Tracer tracer, Propagator propagator) {
        return new TraceparentResponseFilter(tracer, propagator);
    }

    @Bean
    MeterFilter approvedMetricFilter() {
        return MeterFilter.denyUnless(OneCardCommonAutoConfiguration::isApprovedMetric);
    }

    private static boolean isApprovedMetric(Meter.Id id) {
        String name = id.getName();
        return name.equals("http.server.requests")
                || name.equals("http.client.requests")
                || name.startsWith("jvm.")
                || name.startsWith("process.")
                || name.startsWith("system.cpu.")
                || name.startsWith("executor.");
    }
}
