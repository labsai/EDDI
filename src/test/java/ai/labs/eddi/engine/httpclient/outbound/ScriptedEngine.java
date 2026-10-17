/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * An {@link OutboundEngine} that replays canned answers and records every hop
 * the facade asked it to send. Put it behind a real facade with
 * {@link TestOutbound#facade(OutboundEngine)}, so the code under test runs
 * through the facade's actual redirect, SSRF and body handling, without a
 * socket.
 */
public final class ScriptedEngine implements OutboundEngine {

    /** Every hop sent, in order. */
    public final List<Hop> hops = new ArrayList<>();
    private final Deque<Object> answers = new ArrayDeque<>();

    /**
     * Answers the next hop with {@code status}, {@code headers} and {@code body}.
     */
    public ScriptedEngine respond(int status, Map<String, List<String>> headers, InputStream body) {
        answers.add(new Received(status, null, OutboundRequest.caseInsensitiveCopy(headers), body));
        return this;
    }

    public ScriptedEngine respond(int status, Map<String, List<String>> headers, byte[] body) {
        return respond(status, headers, new ByteArrayInputStream(body));
    }

    public ScriptedEngine respond(int status, String body) {
        return respond(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Fails the next hop with {@code failure}, as a transport error would. */
    public ScriptedEngine fail(IOException failure) {
        answers.add(failure);
        return this;
    }

    /** The last hop sent. */
    public Hop lastHop() {
        if (hops.isEmpty()) {
            throw new AssertionError("no hop was sent");
        }
        return hops.getLast();
    }

    @Override
    public Received send(Hop hop) throws IOException {
        hops.add(hop);
        Object answer = answers.poll();
        if (answer == null) {
            throw new AssertionError("unexpected hop to " + hop.uri());
        }
        if (answer instanceof IOException failure) {
            throw failure;
        }
        return (Received) answer;
    }
}
