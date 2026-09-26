/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.RoutingContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    // ─── H5: the approver of a HITL resume ───

    @Test
    @DisplayName("H5: the approver is the caller only inside callAsApprover — not for the rest of the turn")
    void approverIsScopedToApprovedCalls() throws Exception {
        var approver = new CallerIdentity("admin-token", "admin", "https://eddi.example:443");

        Callable<CallerIdentity[]> turn = context.withIdentity(null, context.withApprover(approver, () -> new CallerIdentity[]{
                context.current(),
                context.callAsApprover(context::current),
                context.current()}));
        CallerIdentity[] seen = turn.call();

        assertNull(seen[0], "before the approved call the turn has no caller");
        assertEquals(approver, seen[1], "the approved call runs as the approver");
        assertNull(seen[2], "after it the turn has no caller again");
        assertNull(context.approver(), "the approver binding is restored after the turn");
    }

    @Test
    @DisplayName("callAsApprover with no approver bound keeps the turn's own caller")
    void callAsApproverWithoutApprover() {
        var owner = new CallerIdentity("owner-token", "owner", "https://eddi.example:443");
        context.bind(owner);
        assertEquals(owner, context.callAsApprover(context::current));
    }

    @Test
    @DisplayName("propagate() carries the approver binding across a thread hop")
    void propagateCarriesTheApprover() throws Exception {
        var approver = new CallerIdentity("admin-token", "admin", "https://eddi.example:443");
        var executor = Executors.newSingleThreadExecutor();
        try {
            CallerIdentity seen = context.withApprover(approver, () -> executor.submit(
                    context.propagate(() -> context.callAsApprover(context::current))).get()).call();
            assertEquals(approver, seen);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void isSameUser() {
        var alice = new CallerIdentity("t", "alice", "https://eddi.example:443");
        assertTrue(CallerIdentityContext.isSameUser(alice, "alice"));
        assertFalse(CallerIdentityContext.isSameUser(alice, "bob"));
        assertFalse(CallerIdentityContext.isSameUser(alice, null));
        assertFalse(CallerIdentityContext.isSameUser(null, "alice"));
        assertFalse(CallerIdentityContext.isSameUser(new CallerIdentity("t", null, null), null));
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
    @DisplayName("propagate() carries the turn's ResolutionPrincipal across the hop as well as the caller")
    void propagateCarriesTheResolutionPrincipalToo() throws Exception {
        // A model cascade step and a fire-and-forget batch are dispatched with
        // propagate(). It used to carry only the caller, so a PER_USER connection
        // resolved inside either found no principal and was refused as if the turn
        // were a scheduled run — with advice about scheduled runs.
        var principals = new ResolutionPrincipalContext();
        var principal = new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED);
        context.bind(new CallerIdentity("alice-token", "alice", "https://eddi.example:443"));
        principals.bind(principal);
        try {
            var work = context.propagate(principals::current);
            var executor = Executors.newSingleThreadExecutor();
            try {
                assertEquals(principal, executor.submit(work).get(),
                        "the conversation's principal must reach the dispatched work, or PER_USER connections refuse inside a cascade");
                assertNull(executor.submit(principals::current).get(), "and the pooled thread must not keep it afterwards");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            principals.clear();
        }
    }

    @Test
    @DisplayName("propagate() with no principal bound masks a stale principal on the worker rather than inheriting it")
    void propagateWithoutPrincipalMasksRatherThanInherits() throws Exception {
        var principals = new ResolutionPrincipalContext();
        principals.clear();
        var wrapped = context.propagate(principals::current);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var stale = new ResolutionPrincipal("mallory", ResolutionPrincipal.Provenance.VERIFIED);
            executor.submit(() -> principals.bind(stale)).get();
            assertEquals(stale, executor.submit(principals::current).get(), "the worker really is carrying a stale principal");

            assertNull(executor.submit(wrapped).get(), "must not pick up the previous occupant's conversation owner");
        } finally {
            executor.submit(principals::clear).get();
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
            // Seed the worker's binding directly. Going through withIdentity() would
            // restore the worker's prior null on exit, leaving nothing stale behind —
            // and the assertion below would then hold even if propagate() returned the
            // work unwrapped, i.e. pass for the wrong reason.
            var stale = new CallerIdentity("stale", "mallory", "https://eddi.example:443");
            executor.submit(() -> context.bind(stale)).get();
            assertEquals(stale, executor.submit(context::current).get(), "the worker really is carrying a stale caller");

            assertNull(executor.submit(wrapped).get(), "must not pick up the previous occupant's caller");
        } finally {
            executor.submit(context::clear).get();
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

    // ==================== X-EDDI-Connection-Credential parsing
    // ====================

    /** An authenticated request carrying the given credential header lines. */
    private static CallerIdentityContext requestWith(boolean anonymous, String... credentialLines) {
        var securityIdentity = mock(SecurityIdentity.class);
        when(securityIdentity.isAnonymous()).thenReturn(anonymous);
        when(securityIdentity.getPrincipal()).thenReturn(() -> "alice");
        var headers = HeadersMultiMap.httpHeaders();
        for (String line : credentialLines) {
            headers.add(CallerIdentityContext.CONNECTION_CREDENTIAL_HEADER, line);
        }
        var request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        when(request.scheme()).thenReturn("https");
        var routingContext = mock(RoutingContext.class);
        when(routingContext.request()).thenReturn(request);
        var currentVertxRequest = mock(CurrentVertxRequest.class);
        when(currentVertxRequest.getCurrent()).thenReturn(routingContext);
        return new CallerIdentityContext(securityIdentity, currentVertxRequest);
    }

    @Test
    @DisplayName("the connection name runs to the first space; everything after it is the whole value")
    void credentialSplitsOnTheFirstSpace() {
        var identity = requestWith(false, "acme key-id:secret");
        assertNotNull(identity.capture());
        assertEquals("key-id:secret", identity.capture().connectionCredential("acme"));
    }

    @Test
    @DisplayName("a value containing spaces is kept intact, so 'Bearer abc' needs no escaping")
    void valueWithSpacesIsKeptWhole() {
        assertEquals("Bearer abc def", requestWith(false, "acme Bearer abc def").capture().connectionCredential("acme"));
    }

    @Test
    @DisplayName("a connection supplied twice is dropped entirely rather than resolved by header order")
    void duplicateNameIsDropped() {
        var identity = requestWith(false, "acme first", "acme second", "other keep-me").capture();
        assertNull(identity.connectionCredential("acme"), "which of two credentials a call is made with must never depend on ordering");
        assertEquals("keep-me", identity.connectionCredential("other"), "an unrelated connection on the same request is unaffected");
    }

    @Test
    @DisplayName("more than sixteen credential headers are all dropped")
    void moreThanTheCapIsDroppedWholesale() {
        var sixteen = new String[16];
        for (int i = 0; i < 16; i++) {
            sixteen[i] = "connection-" + i + " value-" + i;
        }
        assertEquals("value-0", requestWith(false, sixteen).capture().connectionCredential("connection-0"), "sixteen is within the cap");

        var seventeen = new String[17];
        for (int i = 0; i < 17; i++) {
            seventeen[i] = "connection-" + i + " value-" + i;
        }
        assertNull(requestWith(false, seventeen).capture().connectionCredential("connection-0"),
                "a hostile client must not make header parsing the expensive part of a request");
    }

    @Test
    @DisplayName("a value over 8192 characters is dropped")
    void overlongValueIsDropped() {
        String atCap = "x".repeat(8192);
        assertEquals(atCap, requestWith(false, "acme " + atCap).capture().connectionCredential("acme"), "8192 is within the cap");
        assertNull(requestWith(false, "acme " + atCap + "x").capture().connectionCredential("acme"));
    }

    @Test
    @DisplayName("a malformed line — no space, or nothing after it — is dropped without affecting the rest")
    void malformedLinesAreDropped() {
        var identity = requestWith(false, "no-space-at-all", "trailing-space ", "good value").capture();
        assertNull(identity.connectionCredential("no-space-at-all"));
        assertNull(identity.connectionCredential("trailing-space"));
        assertEquals("value", identity.connectionCredential("good"));
    }

    @Test
    @DisplayName("an anonymous request's credentials are dropped with the identity — nobody unauthenticated may spend one")
    void anonymousRequestDropsCredentials() {
        assertNull(requestWith(true, "acme key-id:secret").capture(),
                "a caller that has not authenticated must not make EDDI spend a credential on its behalf");
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
