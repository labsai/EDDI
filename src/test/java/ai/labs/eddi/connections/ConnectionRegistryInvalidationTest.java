/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.model.ConnectionReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order in which {@link ConnectionRegistry#invalidateLocal} drops its
 * documents and runs its listeners (#1179).
 */
class ConnectionRegistryInvalidationTest {

    private static final ConnectionReference REFERENCE = new ConnectionReference("default", "peer");

    @Test
    @DisplayName("listeners run before the documents are dropped, so no caller can load the new binding while a listener's cache still holds the old one")
    void listenersRunBeforeAndAfterTheDrop() throws Exception {
        IConnectionStore store = mock(IConnectionStore.class);
        when(store.readByName(anyString(), anyString())).thenReturn(new ConnectionConfiguration());
        var registry = new ConnectionRegistry(store);
        registry.find(REFERENCE); // cached: one store read
        List<Integer> readsSeenByListener = new ArrayList<>();
        registry.onInvalidation(() -> {
            // A caller reloading the binding from inside the listener: the first run must
            // still find the cached document, i.e. nothing was dropped before it.
            registry.find(REFERENCE);
            readsSeenByListener.add(mockingReads(store));
        });

        registry.invalidateLocal();

        assertEquals(List.of(1, 2), readsSeenByListener,
                "first run before the drop (still cached), second after it (read again)");
        verify(store, times(2)).readByName("default", "peer");
    }

    private static int mockingReads(IConnectionStore store) {
        return (int) mockingDetails(store).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("readByName")).count();
    }
}
