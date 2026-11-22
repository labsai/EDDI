/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /**
     * Callers never close the clients the factory hands out, so the
     * {@code currentversion} client is built once per store and shared rather than
     * once per routed client.
     */
    @Test
    void theCurrentVersionClientIsBuiltOncePerStoreAndTrustLevel() {
        var factory = new RestInterfaceFactory(7070, "0.0.0.0", mock(LoopbackCallerAuthFilter.class));
        List<URI> built = new ArrayList<>();
        var store = URI.create("http://127.0.0.1:7070/workflowstore/workflows");
        var other = URI.create("http://127.0.0.1:7070/llmstore/llms");

        var first = factory.currentVersionEndpoint(store, true, uri -> {
            built.add(uri);
            return id -> "1";
        });
        var again = factory.currentVersionEndpoint(store, true, uri -> {
            built.add(uri);
            return id -> "2";
        });
        var anonymous = factory.currentVersionEndpoint(store, false, uri -> {
            built.add(uri);
            return id -> "3";
        });
        factory.currentVersionEndpoint(other, true, uri -> {
            built.add(uri);
            return id -> "4";
        });

        assertSame(first, again);
        assertNotSame(first, anonymous);
        assertEquals(List.of(store, store, other), built);
    }
}
