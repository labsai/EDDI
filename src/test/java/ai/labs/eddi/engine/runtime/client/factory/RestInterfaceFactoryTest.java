/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RestInterfaceFactoryTest {

    /**
     * Bound to one specific address, EDDI is not listening on 127.0.0.1 — whatever
     * is would have received every caller's bearer token.
     */
    @Test
    void addressesTheListenerItIsBoundTo() {
        var factory = new RestInterfaceFactory(7070, "10.0.0.5",
                mock(LoopbackCallerAuthFilter.class));

        assertEquals("http://10.0.0.5:7070", factory.baseUri());
        assertTrue(factory.forwardsCallerCredentials());
    }

    @Test
    void wildcardBindStaysOnLoopback() {
        var factory = new RestInterfaceFactory(7070, "0.0.0.0",
                mock(LoopbackCallerAuthFilter.class));

        assertEquals("http://127.0.0.1:7070", factory.baseUri());
    }

    @Test
    void anUnresolvableSelfAddressForwardsNoCredential() {
        var factory = new RestInterfaceFactory(0, "0.0.0.0", mock(LoopbackCallerAuthFilter.class));

        assertFalse(factory.forwardsCallerCredentials());
    }
}
