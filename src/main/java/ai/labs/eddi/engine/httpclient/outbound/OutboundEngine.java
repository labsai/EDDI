/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
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

    /** Releases the engine's connection pools. Called once, at shutdown. */
    default void close() {
    }

    /**
     * One hop on the wire.
     *
     * @param hopTimeout
     *            the engine's own response-header timeout for this hop
     * @param deadlineNanos
     *            the {@link System#nanoTime()} by which the headers must have
     *            arrived — what is left of the whole exchange's budget
     * @param peerCheck
     *            run on the address the connection actually reached, after the
     *            connection is assigned and before a byte of the request is
     *            written; {@code null} for none. An engine that cannot see the
     *            connected peer (the JDK one) ignores it — {@link OutboundHttp}
     *            rechecks after the exchange either way. A request routed through a
     *            forward proxy skips it too: its peer is the proxy
     */
    record Hop(Profile profile, String method, URI uri, Map<String, List<String>> headers, byte[] body,
            Duration hopTimeout, long deadlineNanos, PeerCheck peerCheck) {

        /** A hop without a connected-peer check. */
        public Hop(Profile profile, String method, URI uri, Map<String, List<String>> headers, byte[] body,
                Duration hopTimeout, long deadlineNanos) {
            this(profile, method, uri, headers, body, hopTimeout, deadlineNanos, null);
        }
    }

    /**
     * Vetoes the address a connection reached. Runs on an engine thread (for the
     * Vert.x engine, the event loop), so it must not block: it judges an IP literal
     * and never resolves a name.
     */
    @FunctionalInterface
    interface PeerCheck {
        /**
         * @param peer
         *            the connection's remote address, or {@code null} when it cannot be
         *            read
         * @throws OutboundBlockedException
         *             when the request must not be sent to it
         */
        void check(InetAddress peer);
    }

    /**
     * A response whose headers have arrived. {@code body} is unread; closing it
     * before the end aborts the exchange and releases the connection.
     */
    record Received(int status, String reasonPhrase, Map<String, List<String>> headers, InputStream body) {
    }
}
