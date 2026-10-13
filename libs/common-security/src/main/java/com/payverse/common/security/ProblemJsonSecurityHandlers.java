package com.payverse.common.security;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.web.ProblemDetailsExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/** 401/403 responses rendered as RFC 9457 Problem Details, matching the rest of the API. */
public final class ProblemJsonSecurityHandlers {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private ProblemJsonSecurityHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, CommonErrorCode.UNAUTHORIZED, "A valid bearer token is required", request.getRequestURI());
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                write(response, CommonErrorCode.FORBIDDEN, "You do not have permission to access this resource", request.getRequestURI());
    }

    private static void write(HttpServletResponse response, CommonErrorCode code, String detail, String path) throws IOException {
        ProblemDetail problem = ProblemDetailsExceptionHandler.problem(code, detail);
        problem.setInstance(URI.create(path));
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        MAPPER.writeValue(response.getOutputStream(), problem);
    }
}
