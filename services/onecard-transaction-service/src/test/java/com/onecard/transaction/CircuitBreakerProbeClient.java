package com.onecard.transaction;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "service-circuit-probe", url = "${service.auth-test-target-url}")
interface CircuitBreakerProbeClient {

    @GetMapping("/internal/circuit-probe")
    String probe();
}
