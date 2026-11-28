/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Imports, previews and syncs each hold real memory while they run; any number
 * of them used to run side by side.
 */
@DisplayName("BackupConcurrencyGate — concurrent imports and syncs")
class BackupConcurrencyGateTest {

    @Test
    @DisplayName("an operation past the limit is refused with 429 naming the property, while the others run")
    void refusesPastTheLimit() throws Exception {
        BackupConcurrencyGate gate = new BackupConcurrencyGate(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            String outcome = gate.run(() -> {
                // The one slot is held here; a second request from another worker is refused.
                ExecutionException refused = assertThrows(ExecutionException.class,
                        () -> other.submit(() -> gate.run(() -> "second")).get(10, TimeUnit.SECONDS));
                WebApplicationException e = assertInstanceOf(WebApplicationException.class, refused.getCause());
                assertEquals(429, e.getResponse().getStatus());
                assertTrue(e.getMessage().contains(BackupConcurrencyGate.MAX_CONCURRENT_PROPERTY), e.getMessage());
                assertEquals(BackupConcurrencyGate.RETRY_AFTER_SECONDS, e.getResponse().getHeaderString("Retry-After"));
                return "first";
            });

            assertEquals("first", outcome);
            assertEquals("again", other.submit(() -> gate.run(() -> "again")).get(10, TimeUnit.SECONDS),
                    "the slot is free again once the first finished");
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("a call made from inside an admitted one runs on its slot instead of being refused")
    void nestedCallReusesTheSlot() throws Exception {
        BackupConcurrencyGate gate = new BackupConcurrencyGate(1);

        String outcome = gate.run(() -> gate.run(() -> gate.run(() -> "nested")));

        assertEquals("nested", outcome);
        assertEquals(1, gate.available(), "every slot is returned after the outermost call");
    }

    @Test
    @DisplayName("an operation that fails gives its slot back")
    void failureReleasesTheSlot() {
        BackupConcurrencyGate gate = new BackupConcurrencyGate(2);

        assertThrows(IllegalStateException.class, () -> gate.run(() -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(2, gate.available());
    }

    @Test
    @DisplayName("a limit that is not positive falls back to the default")
    void nonPositiveLimitFallsBack() {
        assertEquals(BackupConcurrencyGate.DEFAULT_MAX_CONCURRENT, new BackupConcurrencyGate(0).maxConcurrent());
    }

    @Test
    @DisplayName("the interceptor runs the intercepted call through the gate")
    void interceptorDelegatesToTheGate() throws Exception {
        BackupConcurrencyInterceptor interceptor = new BackupConcurrencyInterceptor();
        interceptor.gate = new BackupConcurrencyGate(1);
        InvocationContext context = mock(InvocationContext.class);
        when(context.proceed()).thenAnswer(invocation -> interceptor.gate.available());

        assertEquals(0, interceptor.limit(context), "the call ran while holding the slot");
        assertEquals(1, interceptor.gate.available());
    }
}
