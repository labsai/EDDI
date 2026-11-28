/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgPropagationTest {

    static final OrgId ACME = OrgId.of("acme");
    static final OrgId GLOBEX = OrgId.of("globex");

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        OrgContext.configure(OrgMode.MULTI, null);
        // one pooled thread, so a task that leaked a binding would show it to the next
        // one
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        OrgContext.configure(OrgMode.SINGLE, OrgContextObserver.NONE);
    }

    @Test
    void wrappedRunnableSeesTheSubmittersOrgOnAnotherThread() throws Exception {
        AtomicReference<Optional<OrgId>> seen = new AtomicReference<>();

        Runnable task = OrgContext.callAs(ACME, () -> OrgPropagation.wrap(() -> seen.set(OrgContext.current())));
        pool.submit(task).get();

        assertEquals(Optional.of(ACME), seen.get());
    }

    @Test
    void unwrappedWorkOnAnotherThreadIsUnbound() throws Exception {
        AtomicReference<Optional<OrgId>> seen = new AtomicReference<>();

        OrgContext.callAs(ACME, () -> pool.submit(() -> seen.set(OrgContext.current())).get());

        assertEquals(Optional.empty(), seen.get());
    }

    @Test
    void wrappedCallableAndSupplierCarryTheOrg() throws Exception {
        Callable<OrgId> callable = OrgContext.callAs(ACME, () -> OrgPropagation.wrap((Callable<OrgId>) OrgContext::require));
        Supplier<OrgId> supplier = OrgContext.callAs(GLOBEX, () -> OrgPropagation.wrapSupplier(OrgContext::require));

        assertEquals(ACME, pool.submit(callable).get());
        assertEquals(GLOBEX, CompletableFuture.supplyAsync(supplier, pool).get());
    }

    @Test
    void wrappedExecutorCapturesAtSubmissionNotAtCreation() throws Exception {
        var executor = OrgPropagation.wrap(pool);
        AtomicReference<Optional<OrgId>> seen = new AtomicReference<>();

        OrgContext.callAs(GLOBEX, () -> CompletableFuture.runAsync(() -> seen.set(OrgContext.current()), executor).get());

        assertEquals(Optional.of(GLOBEX), seen.get());
    }

    @Test
    void workCapturedUnboundRunsUnboundOnAnUnboundThread() throws Exception {
        Callable<Optional<OrgId>> unbound = OrgPropagation.wrap((Callable<Optional<OrgId>>) OrgContext::current);

        assertEquals(Optional.empty(), pool.submit(unbound).get());
    }

    @Test
    void workCapturedUnboundKeepsTheOrgOfABoundThreadAndCannotCrossFromIt() throws Exception {
        Callable<Optional<OrgId>> unbound = OrgPropagation.wrap((Callable<Optional<OrgId>>) OrgContext::current);
        Runnable crosses = OrgPropagation.wrap(() -> OrgContext.runAs(GLOBEX, () -> {
        }));

        assertEquals(Optional.of(ACME), OrgContext.callAs(ACME, unbound));
        assertThrows(OrgBindingException.class, () -> OrgContext.runAs(ACME, crosses));
    }

    @Test
    void sameOrgWorkRunsUnderTheThreadsEpoch() throws Exception {
        Callable<Long> capturedEpoch = OrgContext.callAs(ACME, () -> OrgPropagation.wrap((Callable<Long>) OrgContext::epoch));

        long[] epochs = OrgContext.callAs(ACME, () -> new long[]{OrgContext.epoch(), capturedEpoch.call()});

        assertEquals(epochs[0], epochs[1]);
    }

    @Test
    void onAPlatformThreadPlatformWorkKeepsTheThreadsEpoch() throws Exception {
        Callable<Long> captured = OrgScope.platform("test:a", () -> OrgPropagation.wrap((Callable<Long>) OrgContext::epoch));

        long[] epochs = OrgScope.platform("test:b", () -> new long[]{OrgContext.epoch(), captured.call()});

        assertEquals(epochs[0], epochs[1]);
    }

    @Test
    void onAPlatformThreadOrgWorkBindsItsOrg() throws Exception {
        Callable<OrgId> forAcme = OrgContext.callAs(ACME, () -> OrgPropagation.wrap((Callable<OrgId>) OrgContext::require));

        assertEquals(ACME, OrgScope.platform("test:per-item", forAcme));
    }

    @Test
    void onAPlatformThreadUnboundWorkShedsThePlatformPlane() throws Exception {
        Callable<Boolean> unbound = OrgPropagation.wrap((Callable<Boolean>) OrgContext::isPlatform);
        Callable<Long> unboundEpoch = OrgPropagation.wrap((Callable<Long>) OrgContext::epoch);

        assertEquals(Boolean.FALSE, OrgScope.platform("test:shed", unbound));
        assertEquals(0L, OrgScope.platform("test:shed", unboundEpoch));
        assertThrows(OrgContextMissingException.class,
                () -> OrgScope.platform("test:shed", OrgPropagation.wrap((Callable<OrgId>) OrgContext::require)));
    }

    @Test
    void platformWorkRefusesToRunInlineOnAnOrgBoundThread() throws Exception {
        Runnable platformWork = OrgScope.platform("test:inline", () -> OrgPropagation.wrap(() -> {
        }));

        assertThrows(OrgBindingException.class, () -> OrgContext.runAs(ACME, platformWork));
    }

    @Test
    void workForOneOrgRefusesToRunInlineOnAThreadBoundToAnother() throws Exception {
        Runnable forAcme = OrgContext.callAs(ACME, () -> OrgPropagation.wrap(() -> {
        }));

        assertThrows(OrgBindingException.class, () -> OrgContext.runAs(GLOBEX, forAcme));
    }

    @Test
    void platformCapturedWorkRestoresThePlatformPlane() throws Exception {
        Callable<Boolean> task = OrgScope.platform("test:propagation", () -> OrgPropagation.wrap((Callable<Boolean>) OrgContext::isPlatform));

        assertTrue(pool.submit(task).get());
    }

    @Test
    void runtimeExceptionsPassThroughTheWrapper() {
        IllegalStateException boom = new IllegalStateException("boom");
        Runnable failing = OrgPropagation.wrap((Runnable) () -> {
            throw boom;
        });

        assertEquals(boom, assertThrows(IllegalStateException.class, failing::run));
    }
}
