package com.onecard.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 2)
final class TraceparentResponseFilter extends OncePerRequestFilter {

    private final Tracer tracer;
    private final Propagator propagator;

    TraceparentResponseFilter(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Span currentSpan = tracer.currentSpan();
        String inboundTraceparent = request.getHeader("traceparent");
        if (currentSpan != null && hasAcceptedParent(inboundTraceparent, currentSpan.context().traceId())) {
            propagator.inject(currentSpan.context(), response,
                    (carrier, key, value) -> carrier.setHeader(key, value));
        }
        filterChain.doFilter(request, response);
    }

    private static boolean hasAcceptedParent(String traceparent, String activeTraceId) {
        return traceparent != null
                && traceparent.length() >= 35
                && traceparent.charAt(2) == '-'
                && traceparent.regionMatches(true, 3, activeTraceId, 0, 32);
    }
}
