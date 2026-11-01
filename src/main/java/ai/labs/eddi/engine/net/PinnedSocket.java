/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * A socket that can only connect to the address its endpoint was vetted with.
 * <p>
 * A mail library connects with {@code new InetSocketAddress(host, port)}, which
 * resolves the name again — and a second DNS answer is how a rebinding attack
 * turns a vetted public name into {@code 127.0.0.1}. This socket accepts that
 * call only for the vetted host and port, ignores whatever the second lookup
 * returned, and connects to the address the policy approved.
 */
final class PinnedSocket extends Socket {

    private final SafeSocketConnector.Endpoint endpoint;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    PinnedSocket(SafeSocketConnector.Endpoint endpoint, int connectTimeoutMillis, int readTimeoutMillis) {
        this.endpoint = endpoint;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public void connect(SocketAddress requested) throws IOException {
        connect(requested, 0);
    }

    @Override
    public void connect(SocketAddress requested, int timeout) throws IOException {
        InetSocketAddress target = connectTarget(endpoint, requested);
        int effective = timeout > 0 ? Math.min(timeout, connectTimeoutMillis) : connectTimeoutMillis;
        super.connect(target, effective);
        setSoTimeout(readTimeoutMillis);
    }

    /**
     * Where a connect to {@code requested} really goes: the pinned address, when
     * {@code requested} names the vetted host (or the pinned address itself) and
     * port.
     *
     * @throws SocketPolicyException
     *             for anything else
     */
    static InetSocketAddress connectTarget(SafeSocketConnector.Endpoint endpoint, SocketAddress requested) throws SocketPolicyException {
        if (!(requested instanceof InetSocketAddress inet) || inet.getPort() != endpoint.port()) {
            throw mismatch(endpoint);
        }
        boolean sameHost = endpoint.host().equalsIgnoreCase(stripTrailingDot(inet.getHostString()));
        boolean pinnedAddress = !inet.isUnresolved() && endpoint.address().equals(inet.getAddress());
        if (!sameHost && !pinnedAddress) {
            throw mismatch(endpoint);
        }
        return new InetSocketAddress(endpoint.address(), endpoint.port());
    }

    private static String stripTrailingDot(String host) {
        return host != null && host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static SocketPolicyException mismatch(SafeSocketConnector.Endpoint endpoint) {
        return new SocketPolicyException(SocketPolicyException.Reason.ENDPOINT_MISMATCH,
                "A socket vetted for " + endpoint.host() + ":" + endpoint.port() + " was asked to connect elsewhere.");
    }
}
