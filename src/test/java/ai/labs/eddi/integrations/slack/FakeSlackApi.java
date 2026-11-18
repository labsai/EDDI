/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small in-process Slack Web API: an {@link OutboundEngine} that answers
 * {@code chat.postMessage}, {@code chat.update} and {@code views.open} the way
 * Slack does, and records what it was sent.
 * <p>
 * It sits behind a real {@code OutboundHttp} facade, so
 * {@link SlackWebApiClient} and {@link SlackEventHandler} run through the same
 * redirect, header and body handling as in production, and need no socket —
 * which is why the suites that use it are plain unit tests and not integration
 * tests. Point the client at it with {@code eddi.slack.api-base-url}
 * ({@link #BASE_URL}).
 * <p>
 * Behaviour is scripted per call: by default every call succeeds with
 * {@code ok:true} and a fresh {@code ts}; {@link #next} queues answers that are
 * used first, in order.
 */
final class FakeSlackApi implements OutboundEngine {

    /** The base URL a client under test is configured with. */
    static final String BASE_URL = "https://slack.test/api/";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One call Slack received. */
    record Call(String method, String authorization, JsonNode body) {
        String channel() {
            return body.path("channel").asText(null);
        }

        String threadTs() {
            return body.path("thread_ts").asText(null);
        }

        String text() {
            return body.path("text").asText(null);
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Deque<Received> scripted = new ArrayDeque<>();
    private int sequence;

    /** HTTP 200 with {@code {"ok":true}} and a fresh {@code ts}. */
    FakeSlackApi nextOk() {
        return next(200, Map.of(), null);
    }

    /** HTTP 200 with {@code ok:false} and Slack's error code. */
    FakeSlackApi nextError(String error) {
        return next(200, Map.of(), "{\"ok\":false,\"error\":\"" + error + "\"}");
    }

    /**
     * HTTP 429 with a {@code Retry-After} header, as Slack rate-limits a method.
     */
    FakeSlackApi nextRateLimited(int retryAfterSeconds) {
        return nextRateLimited(Integer.toString(retryAfterSeconds));
    }

    /** HTTP 429 with a {@code Retry-After} header of any text. */
    FakeSlackApi nextRateLimited(String retryAfter) {
        return next(429, Map.of("Retry-After", List.of(retryAfter)), "{\"ok\":false,\"error\":\"ratelimited\"}");
    }

    /** An HTTP status with no Slack body. */
    FakeSlackApi nextStatus(int status) {
        return next(status, Map.of(), "");
    }

    private synchronized FakeSlackApi next(int status, Map<String, List<String>> headers, String body) {
        scripted.add(new Received(status, null, OutboundRequest.caseInsensitiveCopy(headers),
                body == null ? null : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        return this;
    }

    /** Every call received, in order. */
    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The calls to one Slack method, e.g. {@code chat.postMessage}. */
    List<Call> calls(String method) {
        return calls.stream().filter(call -> call.method().equals(method)).toList();
    }

    @Override
    public synchronized Received send(Hop hop) throws IOException {
        URI uri = hop.uri();
        String path = uri.getPath();
        String method = path.substring(path.lastIndexOf('/') + 1);
        if (!uri.toString().startsWith(BASE_URL)) {
            throw new AssertionError("the client called " + uri + ", not the configured fake at " + BASE_URL);
        }
        String authorization = hop.headers().getOrDefault("Authorization", List.of()).stream().findFirst().orElse(null);
        JsonNode body = JSON.readTree(hop.body());
        calls.add(new Call(method, authorization, body));
        if (!METHODS.contains(method)) {
            throw new AssertionError("the client called an unknown Slack method: " + method);
        }
        // What Slack itself refuses, it refuses here too, so a test cannot pass on a
        // call the real API would turn down.
        String refusal = refusal(method, authorization, body);
        if (refusal != null) {
            return new Received(200, null, OutboundRequest.caseInsensitiveCopy(Map.of()),
                    new ByteArrayInputStream(("{\"ok\":false,\"error\":\"" + refusal + "\"}").getBytes(StandardCharsets.UTF_8)));
        }

        Received answer = scripted.poll();
        if (answer != null && answer.body() != null) {
            return answer;
        }
        if (answer != null) {
            // Scripted as "ok" without a body: build it now so every ts is distinct.
            return new Received(answer.status(), null, answer.headers(), ok());
        }
        return new Received(200, null, OutboundRequest.caseInsensitiveCopy(Map.of()), ok());
    }

    /** The Web API methods EDDI calls. */
    private static final Set<String> METHODS = Set.of("chat.postMessage", "chat.update", "views.open");

    /** Slack's limit on a message's {@code text}. */
    static final int MAX_TEXT_LENGTH = 40_000;

    /**
     * The error Slack would answer, or {@code null} when it would accept the call.
     */
    private static String refusal(String method, String authorization, JsonNode body) {
        if (authorization == null || !authorization.startsWith("Bearer xoxb-") || authorization.length() <= "Bearer xoxb-".length()) {
            return "not_authed";
        }
        if (method.startsWith("chat.") && body.path("channel").asText("").isBlank()) {
            return "channel_not_found";
        }
        if (method.equals("chat.postMessage") && body.path("text").asText("").isBlank() && !body.has("blocks")) {
            return "no_text";
        }
        if (body.path("text").asText("").length() > MAX_TEXT_LENGTH) {
            return "msg_too_long";
        }
        return null;
    }

    private ByteArrayInputStream ok() {
        sequence++;
        String body = "{\"ok\":true,\"ts\":\"1700000000." + String.format("%06d", sequence) + "\"}";
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
