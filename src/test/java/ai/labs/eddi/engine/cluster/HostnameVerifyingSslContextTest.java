/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostnameVerifyingSslContextTest {

    /**
     * jnats upgrades its socket through {@code getSocketFactory()} and sets no
     * endpoint identification algorithm; the plain JDK context leaves it unset,
     * which accepts a trusted certificate issued for any host.
     */
    @Test
    void socketsFromTheFactoryVerifyTheHostName() throws Exception {
        SSLContext plain = SSLContext.getInstance("TLS");
        plain.init(null, null, null);
        try (SSLSocket unverified = (SSLSocket) plain.getSocketFactory().createSocket()) {
            assertNull(unverified.getSSLParameters().getEndpointIdentificationAlgorithm(), "precondition: the JDK default does not verify");
        }

        SSLContext verifying = new HostnameVerifyingSslContext(plain);
        try (SSLSocket socket = (SSLSocket) verifying.getSocketFactory().createSocket()) {
            assertEquals("HTTPS", socket.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
        assertEquals("HTTPS", verifying.createSSLEngine("nats.example", 4222).getSSLParameters().getEndpointIdentificationAlgorithm());
    }
}
