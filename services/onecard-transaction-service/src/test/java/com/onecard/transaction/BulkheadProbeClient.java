package com.onecard.transaction;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "service-bulkhead-probe", url = "${service.auth-test-target-url}")
interface BulkheadProbeClient {

    @GetMapping("/internal/bulkhead-probe")
    String probe();
}
