package com.wealthview.app.config;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

final class HttpClientFactory {

    private HttpClientFactory() {
    }

    static ClientHttpRequestFactory withTimeouts(Duration connect, Duration read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }
}
