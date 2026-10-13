package com.payverse.common.error.web;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Maps every exception to an RFC 9457 Problem Detail with a consistent shape:
 * {@code type, title, status, detail, instance, code, correlationId, timestamp[, errors]}.
 * Internal details (stack traces, SQL, class names) are never exposed.
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(PayverseException.class)
    public ResponseEntity<ProblemDetail> handlePayverse(PayverseException ex) {
        ProblemDetail problem = problem(ex.errorCode(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        if (ex.errorCode().httpStatus() >= 500) {
            log.error("Request failed with {}", ex.errorCode().code(), ex);
        } else {
            log.debug("Request rejected with {}: {}", ex.errorCode().code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ProblemDetail problem = problem(CommonErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(CommonErrorCode.CONCURRENT_MODIFICATION, null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = problem(CommonErrorCode.MALFORMED_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(problem(CommonErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(CommonErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new FieldViolation(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(CommonErrorCode.VALIDATION_FAILED, "One or more parameters are invalid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            ErrorCode code = "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName())
                    ? CommonErrorCode.IDEMPOTENCY_KEY_MISSING : CommonErrorCode.MALFORMED_REQUEST;
            return ResponseEntity.badRequest().body(problem(code, "Missing required header '" + missing.getHeaderName() + "'"));
        }
        return ResponseEntity.badRequest().body(problem(CommonErrorCode.MALFORMED_REQUEST, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(CommonErrorCode.MALFORMED_REQUEST, "Request body is missing or malformed"));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(CommonErrorCode.NOT_FOUND, "No endpoint " + ex.getResourcePath()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpHeaders headers, HttpStatusCode status,
                                                                         WebRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(problem(CommonErrorCode.METHOD_NOT_ALLOWED, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(headers)
                .body(problem(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, ex.getMessage()));
    }

    /** Fallback for any other Spring MVC exception: keep Spring's status, apply our shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd && pd.getProperties() == null) {
            ErrorCode code = statusCode.value() >= 500 ? CommonErrorCode.INTERNAL_ERROR : CommonErrorCode.MALFORMED_REQUEST;
            decorate(pd, code);
        }
        return new ResponseEntity<>(body, headers, statusCode);
    }

    public static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus()),
                detail != null ? detail : code.title());
        decorate(problem, code);
        return problem;
    }

    private static void decorate(ProblemDetail problem, ErrorCode code) {
        problem.setType(ProblemTypes.of(code));
        problem.setTitle(code.title());
        problem.setProperties(new java.util.LinkedHashMap<>(Map.of("code", code.code(), "timestamp", Instant.now().toString())));
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
