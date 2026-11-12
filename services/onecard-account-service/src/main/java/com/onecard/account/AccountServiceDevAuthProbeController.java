package com.onecard.account;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "onecard.dev.service-auth-probe.enabled", havingValue = "true")
class AccountServiceDevAuthProbeController {

    @GetMapping("/internal/dev/service-auth-probe")
    Map<String, String> verifyServiceAuthentication() {
        return Map.of("status", "authenticated");
    }
}
