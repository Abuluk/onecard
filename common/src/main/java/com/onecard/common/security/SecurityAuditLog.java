package com.onecard.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SecurityAuditLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.onecard.security.audit");

    private SecurityAuditLog() {
    }

    public static void authenticationFailed(String service, String method) {
        LOGGER.warn("security_event event=authentication_failed service={} method={}", service, method);
    }

    public static void accessDenied(String service, String method) {
        LOGGER.warn("security_event event=access_denied service={} method={}", service, method);
    }
}
