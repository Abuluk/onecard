package com.onecard.account;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RefreshScope
class AccountConfigProbeController {

    private final String value;

    AccountConfigProbeController(@Value("${onecard.dev.config-probe.value:unset}") String value) {
        this.value = value;
    }

    @GetMapping("/internal/dev/config-probe")
    Map<String, String> readConfigProbeValue() {
        return Map.of("value", value);
    }
}
