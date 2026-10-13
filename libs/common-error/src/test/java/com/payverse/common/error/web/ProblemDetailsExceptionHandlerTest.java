package com.payverse.common.error.web;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProblemDetailsExceptionHandlerTest {

    enum TestErrorCode implements ErrorCode {
        INSUFFICIENT_BALANCE;

        @Override
        public String code() {
            return "WALLET_INSUFFICIENT_BALANCE";
        }

        @Override
        public int httpStatus() {
            return 422;
        }

        @Override
        public String title() {
            return "Insufficient balance";
        }
    }

    record CreateRequest(@NotBlank String name, @Positive int amount) {
    }

    @RestController
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new PayverseException(TestErrorCode.INSUFFICIENT_BALANCE, "Balance too low", Map.of("walletId", "w-1"));
        }

        @GetMapping("/missing/{id}")
        void missing(@PathVariable String id) {
            throw PayverseException.notFound("Wallet", id);
        }

        @GetMapping("/lock")
        void lock() {
            throw new OptimisticLockingFailureException("row changed");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("db password is hunter2");
        }

        @GetMapping("/number/{n}")
        int number(@PathVariable int n) {
            return n;
        }

        @PostMapping("/create")
        CreateRequest create(@Valid @RequestBody CreateRequest request) {
            return request;
        }

        @PostMapping("/pay")
        String pay(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ProblemDetailsExceptionHandler())
                .setValidator(validator)
                .build();
        MDC.put(ProblemDetailsExceptionHandler.CORRELATION_ID_MDC_KEY, "corr-123");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void businessExceptionBecomesProblemDetail() throws Exception {
        mvc.perform(get("/business"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://payverse.dev/problems/wallet-insufficient-balance"))
                .andExpect(jsonPath("$.title").value("Insufficient balance"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Balance too low"))
                .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.walletId").value("w-1"))
                .andExpect(jsonPath("$.correlationId").value("corr-123"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void notFound() throws Exception {
        mvc.perform(get("/missing/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.id").value("42"));
    }

    @Test
    void optimisticLockIsConflict() throws Exception {
        mvc.perform(get("/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        String body = mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("hunter2").doesNotContain("IllegalStateException");
    }

    @Test
    void beanValidationErrorsListFields() throws Exception {
        mvc.perform(post("/create").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void malformedJson() throws Exception {
        mvc.perform(post("/create").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void typeMismatch() throws Exception {
        mvc.perform(get("/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void missingIdempotencyKey() throws Exception {
        mvc.perform(post("/pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
    }

    @Test
    void methodNotAllowed() throws Exception {
        mvc.perform(post("/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/create").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void problemTypeUri() {
        assertThat(ProblemTypes.of(CommonErrorCode.RATE_LIMITED).toString())
                .isEqualTo("https://payverse.dev/problems/rate-limited");
    }

    @Test
    void exceptionDefaults() {
        PayverseException ex = new PayverseException(CommonErrorCode.CONFLICT, null);
        assertThat(ex.getMessage()).isEqualTo("Conflict");
        assertThat(ex.properties()).isEmpty();
        assertThat(PayverseException.businessRule("nope").errorCode()).isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
    }
}
