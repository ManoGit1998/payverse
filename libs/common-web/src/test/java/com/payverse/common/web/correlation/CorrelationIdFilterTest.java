package com.payverse.common.web.correlation;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsValidIncomingIdAndExposesItInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(CorrelationId.MDC_KEY)));

        assertThat(seen.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void replacesMissingOrMaliciousIds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "bad\nvalue<script>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationId.HEADER)).hasSize(36).doesNotContain("script");
    }

    @Test
    void clientInterceptorPropagatesCurrentId() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "corr-77");
        try {
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://wallet/x"));
            ClientHttpRequestExecution execution = (req, body) -> new MockClientHttpResponse(new byte[0], 200);
            new CorrelationIdClientInterceptor().intercept(request, new byte[0], execution);
            assertThat(request.getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo("corr-77");
        } finally {
            MDC.clear();
        }
    }
}
