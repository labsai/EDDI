/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Thread-binding behaviour of {@link CallerIdentityContext}.
 * <p>
 * The binding lives in a {@link ThreadLocal} on threads that are pooled across
 * conversations, so leaking a token to the next turn is the failure mode worth
 * testing hardest.
 *
 * @author ginccc
 */
class CallerIdentityContextTest {

    private final CallerIdentityContext context = new CallerIdentityContext(null, null);

    @AfterEach
    void tearDown() {
        context.clear();
    }

    @Test
    void bindsAndReadsBack() {
        var identity = new CallerIdentity("tok", "alice", "https://eddi.example:443");
        context.bind(identity);
        assertEquals(identity, context.current());
    }

    @Test
    @DisplayName("clear() removes the binding so a pooled thread cannot leak it")
    void clearRemovesBinding() {
        context.bind(new CallerIdentity("tok", "alice", "https://eddi.example:443"));
        context.clear();
        assertNull(context.current());
    }

    @Test
    @DisplayName("binding null clears rather than storing an empty identity")
    void bindNullClears() {
        context.bind(new CallerIdentity("tok", "alice", "https://eddi.example:443"));
        context.bind(null);
        assertNull(context.current());
    }

    @Test
    @DisplayName("one thread's caller is invisible to another")
    void isolatesThreads() throws Exception {
        context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));

        var seenOnOtherThread = new AtomicReference<CallerIdentity>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> seenOnOtherThread.set(context.current())).get();
        } finally {
            executor.shutdownNow();
        }

        assertNull(seenOnOtherThread.get(), "a second thread must not observe another caller's token");
        assertNotNull(context.current());
    }

    @Test
    @DisplayName("capture() outside a request yields no identity instead of failing")
    void captureWithoutRequestContext() {
        // Scheduled jobs and triggers drive turns with no HTTP request at all.
        assertNull(context.capture());
    }

    @Test
    @DisplayName("an unbound thread cannot satisfy a token reference")
    void unboundThreadCannotResolveToken() {
        var resolver = new CallerIdentityResolver(context, true);
        assertThrows(CallerIdentityResolver.CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("https://eddi.example/x")));
    }

    @Test
    @DisplayName("propagate() carries the caller across a thread hop")
    void propagateCarriesIdentityToAnotherThread() throws Exception {
        var identity = new CallerIdentity("alice-token", "alice", "https://eddi.example:443");
        context.bind(identity);

        var work = context.propagate(context::current);
        var executor = Executors.newSingleThreadExecutor();
        try {
            // Without propagation this would be null — the failure mode that makes
            // a fire-and-forget batch fail closed for no visible reason.
            assertEquals(identity, executor.submit(work).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("propagate() clears the borrowed thread afterwards")
    void propagateClearsAfterRunning() throws Exception {
        context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
        var work = context.propagate(() -> "done");

        var leaked = new AtomicReference<CallerIdentity>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(work).get();
            executor.submit(() -> leaked.set(context.current())).get();
        } finally {
            executor.shutdownNow();
        }
        assertNull(leaked.get(), "the pooled thread must not keep the caller's token");
    }

    @Test
    @DisplayName("propagate() returns the work untouched when nothing is bound")
    void propagateIsPassThroughWithoutIdentity() throws Exception {
        Callable<String> work = () -> "done";
        assertSame(work, context.propagate(work));
    }

    @Test
    @DisplayName("a bound caller resolves into the header value")
    void boundCallerResolvesIntoHeader() {
        context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
        var resolver = new CallerIdentityResolver(context, true);
        assertEquals("Bearer alice-token",
                resolver.resolveValue("Bearer ${caller:token}", URI.create("https://eddi.example/agentstore/agents/descriptors")));
    }
}
