/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends exactly one HTTP hop. Every policy — SSRF checks, redirects, header
 * stripping, body caps, the whole-exchange deadline — lives in
 * {@link OutboundHttp}; an engine moves bytes and nothing else, so swapping it
 * cannot weaken any guarantee.
 */
public interface OutboundEngine {

    /**
     * Sends {@code hop} and returns once the response headers have arrived, or
     * fails once {@link Hop#deadlineNanos()} passes.
     *
     * @throws OutboundTimeoutException
     *             when the deadline passed first; the exchange was cancelled
     * @throws InterruptedException
     *             when the calling thread was interrupted; the exchange was
     *             cancelled
     */
    Received send(Hop hop) throws IOException, InterruptedException;

    /**
     * One hop on the wire.
     *
     * @param hopTimeout
     *            the engine's own response-header timeout for this hop
     * @param deadlineNanos
     *            the {@link System#nanoTime()} by which the headers must have
     *            arrived — what is left of the whole exchange's budget
     */
    record Hop(Profile profile, String method, URI uri, Map<String, List<String>> headers, byte[] body,
            Duration hopTimeout, long deadlineNanos) {
    }

    /**
     * A response whose headers have arrived. {@code body} is unread; closing it
     * before the end aborts the exchange and releases the connection.
     */
    record Received(int status, String reasonPhrase, Map<String, List<String>> headers, InputStream body) {
    }
}
