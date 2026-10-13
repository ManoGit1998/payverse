package com.payverse.common.security;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.web.ProblemDetailsExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Method-security failures thrown inside controllers → 401/403 problems (instead of the generic 500). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(ProblemDetailsExceptionHandler.problem(CommonErrorCode.FORBIDDEN, null));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException ex) {
        return ResponseEntity.status(401).body(ProblemDetailsExceptionHandler.problem(CommonErrorCode.UNAUTHORIZED, null));
    }
}
