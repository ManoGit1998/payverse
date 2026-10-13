package com.payverse.common.web.correlation;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Propagates the current correlation id on outgoing service-to-service calls. */
public class CorrelationIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        CorrelationId.current().ifPresent(id -> request.getHeaders().set(CorrelationId.HEADER, id));
        return execution.execute(request, body);
    }
}
