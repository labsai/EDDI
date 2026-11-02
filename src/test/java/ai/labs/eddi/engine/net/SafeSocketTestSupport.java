/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import java.net.InetAddress;

/**
 * Test-only access to an endpoint pinned to a loopback test server, for tests
 * outside this package (the IMAP channel's GreenMail IT). Production code
 * cannot do this: an {@code Endpoint} is minted only inside {@code engine.net}
 * by {@link SafeSocketConnector#resolve}, which refuses loopback — this class
 * lives in test sources.
 */
public final class SafeSocketTestSupport {

    private SafeSocketTestSupport() {
    }

    /**
     * An author-policy endpoint for {@code host} pinned to the loopback address on
     * {@code port}.
     */
    public static SafeSocketConnector.Endpoint loopback(String host, int port, SafeSocketConnector.Protocol protocol,
                                                        SafeSocketConnector.TlsMode tlsMode) {
        return new SafeSocketConnector.Endpoint(host, port, protocol, tlsMode, InetAddress.getLoopbackAddress());
    }
}
