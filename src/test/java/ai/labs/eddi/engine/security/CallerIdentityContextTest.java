/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    @DisplayName("propagate() with nothing bound still wraps, so the work sees no caller")
    void propagateWithoutIdentityMasksRatherThanPassingThrough() throws Exception {
        // It would be cheaper to return the work unwrapped, but then it would
        // inherit whatever caller the destination thread happens to carry.
        Callable<CallerIdentity> work = context::current;
        var wrapped = context.propagate(work);
        assertNotSame(work, wrapped);

        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(context.withIdentity(new CallerIdentity("stale", "mallory", "https://eddi.example:443"),
                    (Runnable) () -> {
                        /* leaves the thread carrying mallory if not restored */ }))
                    .get();
            assertNull(executor.submit(wrapped).get(), "must not pick up the previous occupant's caller");
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== Nesting and the null identity ====================

    @Test
    @DisplayName("a null identity masks an existing binding rather than inheriting it")
    void nullIdentityMasksWhateverThePooledThreadCarried() throws Exception {
        // The thread already carries alice — the state a pooled thread is in after
        // serving her turn. Work explicitly dispatched without a caller must not be
        // able to read her token.
        context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));

        var seen = new AtomicReference<CallerIdentity>();
        context.withIdentity(null, (Runnable) () -> seen.set(context.current())).run();

        assertNull(seen.get(), "unauthenticated work must not observe the previous caller");
    }

    @Test
    @DisplayName("a nested wrapper restores the outer caller instead of clearing it")
    void nestedWrapperRestoresTheOuterBinding() throws Exception {
        var outer = new CallerIdentity("outer-token", "outer", "https://eddi.example:443");
        var inner = new CallerIdentity("inner-token", "inner", "https://eddi.example:443");

        context.withIdentity(outer, (Runnable) () -> {
            assertEquals(outer, context.current());
            context.withIdentity(inner, (Runnable) () -> assertEquals(inner, context.current())).run();
            // Clearing here instead of restoring would strand the rest of the outer
            // turn with no caller, and ${caller:token} would start failing mid-turn.
            assertEquals(outer, context.current(), "the outer caller must survive the inner wrapper");
        }).run();

        assertNull(context.current(), "and the thread is left as it was found");
    }

    @Test
    @DisplayName("the Supplier variant nests the same way")
    void nestedSupplierRestoresTheOuterBinding() {
        var outer = new CallerIdentity("outer-token", "outer", "https://eddi.example:443");
        var inner = new CallerIdentity("inner-token", "inner", "https://eddi.example:443");

        context.withIdentity(outer, (Runnable) () -> {
            context.withIdentitySupplying(inner, () -> context.current()).get();
            assertEquals(outer, context.current());
        }).run();
        assertNull(context.current());
    }

    @Test
    @DisplayName("a Runnable dispatch keeps the caller — the group-discussion shape")
    void withIdentityCarriesRunnableAcrossThreads() throws Exception {
        var identity = new CallerIdentity("alice-token", "alice", "https://eddi.example:443");
        var seen = new AtomicReference<CallerIdentity>();
        var leaked = new AtomicReference<CallerIdentity>();

        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(context.withIdentity(identity, () -> seen.set(context.current()))).get();
            executor.submit(() -> leaked.set(context.current())).get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(identity, seen.get());
        assertNull(leaked.get(), "the pooled thread must not keep the caller's token");
    }

    @Test
    @DisplayName("a Supplier dispatch keeps the caller — the parallel-phase shape")
    void withIdentitySupplyingCarriesAcrossThreads() throws Exception {
        var identity = new CallerIdentity("alice-token", "alice", "https://eddi.example:443");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = CompletableFuture
                    .supplyAsync(context.withIdentitySupplying(identity, () -> context.current()), executor).get();
            assertEquals(identity, result);
        } finally {
            executor.shutdownNow();
        }
        assertNull(context.current(), "the dispatching thread must be unaffected");
    }

    @Test
    @DisplayName("captureOrCurrent() falls back to the thread binding off-request")
    void captureOrCurrentFallsBackToBinding() {
        // A group discussion dispatched mid-pipeline has no request context, but the
        // pipeline thread is bound — that binding is what must travel.
        var identity = new CallerIdentity("alice-token", "alice", "https://eddi.example:443");
        context.bind(identity);
        assertEquals(identity, context.captureOrCurrent());
    }

    @Test
    void captureOrCurrentIsNullWhenNeitherIsAvailable() {
        assertNull(context.captureOrCurrent());
    }

    // ==================== capture(): token and principal are independent
    // ====================

    @Test
    @DisplayName("an authenticated caller with no bearer token still yields a usable userId")
    void capturesPrincipalWithoutToken() {
        // Requiring both would make ${caller:userId} unavailable for any auth mode
        // that has a principal but no bearer credential.
        var identity = captureFrom(null, "alice");
        assertNotNull(identity);
        assertEquals("alice", identity.userId());
        assertFalse(identity.hasToken(), "${caller:token} must still fail closed here");
    }

    @Test
    void capturesTokenAndPrincipalTogether() {
        var identity = captureFrom("jwt-abc", "alice");
        assertNotNull(identity);
        assertEquals("jwt-abc", identity.token());
        assertEquals("alice", identity.userId());
    }

    @Test
    @DisplayName("nothing to offer means no identity at all")
    void capturesNothingWhenNeitherTokenNorPrincipal() {
        assertNull(captureFrom(null, null));
    }

    @Test
    void capturesNothingFromAnAnonymousIdentity() {
        var securityIdentity = mock(SecurityIdentity.class);
        when(securityIdentity.isAnonymous()).thenReturn(true);
        assertNull(new CallerIdentityContext(securityIdentity, null).capture());
    }

    /** Build a context over a mocked identity and capture from it. */
    private CallerIdentity captureFrom(String token, String principalName) {
        var securityIdentity = mock(SecurityIdentity.class);
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getCredential(TokenCredential.class)).thenReturn(token != null ? new TokenCredential(token, "bearer") : null);
        when(securityIdentity.getPrincipal()).thenReturn(principalName != null ? () -> principalName : null);
        // No Vert.x request in a unit test, so origin resolves to null — that alone
        // makes ${caller:token} refuse, which the resolver tests cover.
        return new CallerIdentityContext(securityIdentity, null).capture();
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
