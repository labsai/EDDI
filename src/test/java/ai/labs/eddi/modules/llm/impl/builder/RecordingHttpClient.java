/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A langchain4j {@link HttpClient} that records each request and then fails it
 * with a 599, so a provider builder's parameters can be asserted on the request
 * langchain4j really builds — headers and JSON body — without a network or a
 * canned response per provider. It is its own {@link HttpClientBuilder}.
 */
final class RecordingHttpClient implements HttpClient, HttpClientBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<HttpRequest> requests = new ArrayList<>();

    /** The single request sent; fails when there was none. */
    HttpRequest request() {
        if (requests.isEmpty()) {
            throw new AssertionError("no request reached the HTTP client");
        }
        return requests.getLast();
    }

    JsonNode body() {
        try {
            return MAPPER.readTree(request().body());
        } catch (Exception e) {
            throw new AssertionError("request body is not JSON: " + request().body(), e);
        }
    }

    /** The first value of a header, matched case-insensitively, or null. */
    String header(String name) {
        for (Map.Entry<String, List<String>> entry : request().headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        requests.add(request);
        throw new HttpException(599, "recorded by RecordingHttpClient");
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        requests.add(request);
        listener.onError(new HttpException(599, "recorded by RecordingHttpClient"));
    }

    @Override
    public HttpClient build() {
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return null;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        return this;
    }

    @Override
    public Duration readTimeout() {
        return null;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        return this;
    }
}
