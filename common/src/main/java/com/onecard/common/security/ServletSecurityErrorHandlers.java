package com.onecard.common.security;

import com.onecard.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

public final class ServletSecurityErrorHandlers {

    private ServletSecurityErrorHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint(String service, JsonMapper jsonMapper) {
        return (request, response, exception) -> {
            SecurityAuditLog.authenticationFailed(service, request.getMethod());
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "Authentication is required", jsonMapper);
        };
    }

    public static AccessDeniedHandler accessDeniedHandler(String service, JsonMapper jsonMapper) {
        return (request, response, exception) -> {
            SecurityAuditLog.accessDenied(service, request.getMethod());
            write(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access is denied", jsonMapper);
        };
    }

    private static void write(
            HttpServletResponse response, int status, String code, String message, JsonMapper jsonMapper)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), Result.failure(code, message));
    }
}
