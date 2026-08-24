/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The thread binding that decides whose SaaS credentials a turn may spend.
 * <p>
 * This is a {@link ThreadLocal} on pooled threads, so a leak is not a tidiness
 * problem: the next turn to land on that thread resolves the previous
 * conversation owner's grants. Every test below therefore asserts what a
 * <em>later</em> user of the thread sees, not only what the wrapped work saw.
 */
@DisplayName("ResolutionPrincipalContext — binding, restoration and non-leakage")
class ResolutionPrincipalContextTest {

    private static final ResolutionPrincipal PARENT = new ResolutionPrincipal("parent-user", Provenance.VERIFIED);
    private static final ResolutionPrincipal CHILD = new ResolutionPrincipal("child-user", Provenance.SELF_ASSERTED);

    private ResolutionPrincipalContext context;

    @BeforeEach
    void setUp() {
        context = new ResolutionPrincipalContext();
        // The binding lives in a static ThreadLocal, so a previous test on this thread
        // could otherwise seed the state under test.
        context.clear();
    }

    @AfterEach
    void tearDown() {
        context.clear();
    }

    @Nested
    @DisplayName("bind / current / clear")
    class BindCurrentClear {

        @Test
        @DisplayName("a bound principal is what current() returns, and clear() removes it")
        void bindThenClear() {
            context.bind(PARENT);
            assertSame(PARENT, context.current(),
                    "the bound principal must be readable on this thread, or no credential decision can find it");

            context.clear();
            assertNull(context.current(),
                    "clear() must remove the binding; a surviving principal is the next user's credentials");
        }

        @Test
        @DisplayName("bind(null) removes an existing binding rather than doing nothing")
        void bindNullRemoves() {
            // Work that deliberately has no principal — a scheduled run — must not
            // observe whatever ran on this pooled thread before it. A bind(null) that
            // short-circuits to "no change" is exactly that leak.
            context.bind(PARENT);
            context.bind(null);
            assertNull(context.current(),
                    "bind(null) must clear; treating it as a no-op leaves the previous conversation's owner bound for "
                            + "work that is supposed to have no principal at all");
        }
    }

    @Nested
    @DisplayName("withPrincipal restores rather than clears")
    class Restoration {

        @Test
        @DisplayName("a nested Callable turn restores the parent's binding instead of wiping it")
        void callableRestoresPrevious() throws Exception {
            // A sub-agent conversation started from inside a parent's pipeline turn.
            // Clearing on the way out would leave the REST of the parent's turn with no
            // principal, so its own PER_USER connections would start refusing halfway
            // through for no reason the author could see.
            context.bind(PARENT);

            var seenInside = new AtomicReference<ResolutionPrincipal>();
            // Typed local rather than an inline lambda: withPrincipal is overloaded for
            // Callable and Runnable, and naming the type keeps the overload unambiguous.
            Callable<String> work = () -> {
                seenInside.set(context.current());
                return "done";
            };
            Callable<String> nested = context.withPrincipal(CHILD, work);

            assertEquals("done", nested.call(), "the wrapped work's result must be passed through unchanged");
            assertSame(CHILD, seenInside.get(),
                    "the nested turn must run under its own principal, not the parent's");
            assertSame(PARENT, context.current(),
                    "the parent's binding must be restored, not cleared — the parent turn continues after the nested "
                            + "one returns and still needs its own credentials");
        }

        @Test
        @DisplayName("a nested Runnable turn restores the parent's binding instead of wiping it")
        void runnableRestoresPrevious() {
            context.bind(PARENT);

            var seenInside = new AtomicReference<ResolutionPrincipal>();
            Runnable work = () -> seenInside.set(context.current());
            Runnable nested = context.withPrincipal(CHILD, work);
            nested.run();

            assertSame(CHILD, seenInside.get(),
                    "the nested turn must run under its own principal, not the parent's");
            assertSame(PARENT, context.current(),
                    "the Runnable overload must restore the parent's binding just like the Callable one; the two "
                            + "wrap the same pipeline work");
        }

        @Test
        @DisplayName("the binding is restored even when the wrapped Callable throws")
        void callableRestoresAfterThrow() {
            context.bind(PARENT);

            Callable<String> work = () -> {
                throw new IllegalStateException("tool call blew up");
            };
            Callable<String> boom = context.withPrincipal(CHILD, work);

            var thrown = assertThrows(IllegalStateException.class, boom::call,
                    "the failure must propagate — swallowing it would hide the real error");
            assertEquals("tool call blew up", thrown.getMessage(),
                    "the original failure must reach the caller, not be replaced by a bookkeeping error");
            assertSame(PARENT, context.current(),
                    "a throwing turn must not strand the child's principal on this thread; without the finally the "
                            + "parent's remaining work would run as the child");
        }

        @Test
        @DisplayName("the binding is restored even when the wrapped Runnable throws")
        void runnableRestoresAfterThrow() {
            context.bind(PARENT);

            Runnable work = () -> {
                throw new IllegalStateException("tool call blew up");
            };
            Runnable boom = context.withPrincipal(CHILD, work);

            assertThrows(IllegalStateException.class, boom::run,
                    "the failure must propagate — swallowing it would hide the real error");
            assertSame(PARENT, context.current(),
                    "a throwing turn must not strand the child's principal on this thread");
        }
    }

    @Nested
    @DisplayName("The binding does not outlive the work, and does not cross threads")
    class NoLeak {

        @Test
        @DisplayName("a pooled thread is left with no principal once the wrapped turn finishes")
        void pooledThreadIsLeftClean() throws Exception {
            // The real shape of the bug: two turns, two different conversation owners,
            // ONE reused pool thread. If the wrapper does not unbind, the second turn
            // resolves the first user's SaaS credentials.
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var duringFirstTurn = new AtomicReference<ResolutionPrincipal>();
                Callable<Void> firstTurnWork = () -> {
                    duringFirstTurn.set(context.current());
                    return null;
                };
                pool.submit(context.withPrincipal(PARENT, firstTurnWork)).get();

                var duringSecondTurn = new AtomicReference<ResolutionPrincipal>();
                Runnable secondTurn = () -> duringSecondTurn.set(context.current());
                pool.submit(secondTurn).get();

                assertSame(PARENT, duringFirstTurn.get(),
                        "the first turn must actually see its own principal, or the second assertion below would pass "
                                + "for the trivial reason that nothing was ever bound");
                assertNull(duringSecondTurn.get(),
                        "the next turn on the same pool thread must see no principal; a leftover binding is one user's "
                                + "credentials being spent on another user's turn");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a principal bound on one thread is invisible to another thread")
        void bindingIsPerThread() throws Exception {
            context.bind(PARENT);

            var seenOnOtherThread = new AtomicReference<ResolutionPrincipal>();
            var seenOnOtherThreadRan = new AtomicReference<Boolean>(false);
            Thread other = new Thread(() -> {
                seenOnOtherThread.set(context.current());
                seenOnOtherThreadRan.set(true);
            });
            other.start();
            other.join();

            assertEquals(Boolean.TRUE, seenOnOtherThreadRan.get(),
                    "the other thread must actually have run, otherwise the null below proves nothing");
            assertNull(seenOnOtherThread.get(),
                    "a binding must not be shared between threads; two conversations run concurrently and would "
                            + "otherwise resolve each other's credentials");
            assertSame(PARENT, context.current(),
                    "the binding must still be intact on the thread that made it");
        }
    }
}
