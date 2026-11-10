package com.onecard.transaction;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "service-auth-probe", url = "${service.auth-test-target-url}")
public interface OAuth2ProbeClient {

    @GetMapping("/internal/platform-probe")
    String probe();
}
