/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * The compare-and-swap contract every group-concurrency safety property rests
 * on.
 * <p>
 * {@code compareAndSetState}, {@code updateIfState} and
 * {@code casRunningDiscussion} are all implemented once, over
 * {@link IResourceStorage#storeIfFieldEquals}, so their cross-process atomicity
 * is exactly as good as that method is on the active backend. If the two
 * backends disagree even slightly, every CAS-based guarantee in
 * {@code executeDiscussion} and the cadence claim protocol is theoretical on
 * one of them.
 * <p>
 * The behavioural halves of that contract need a live database and are covered
 * by the store integration tests. What is pinned here is the part that can
 * silently rot without one: that a backend cannot forget to implement the
 * method, and that both overloads exist on both backends with the same shape.
 */
@DisplayName("storeIfFieldEquals — cross-backend contract")
class StoreIfFieldEqualsContractTest {

    private static final Set<String> BACKENDS = Set.of("ai.labs.eddi.datastore.mongo.MongoResourceStorage",
            "ai.labs.eddi.datastore.postgres.PostgresResourceStorage");

    @Nested
    @DisplayName("the default must never silently degrade a CAS")
    class NoSilentDegradation {

        /**
         * A default that stored unconditionally would turn every CAS in the group
         * subsystem into a last-writer-wins update, with no error and no test failure —
         * the single most dangerous shape this interface could have.
         */
        @Test
        @DisplayName("the interface default throws rather than storing unconditionally")
        void defaultThrows() throws Exception {
            // A backend that implements nothing but the interface defaults.
            IResourceStorage<Object> unimplemented = mock(IResourceStorage.class, CALLS_REAL_METHODS);

            var thrown = assertThrows(UnsupportedOperationException.class,
                    () -> unimplemented.storeIfFieldEquals(null, "state", "IN_PROGRESS"));
            assertTrue(thrown.getMessage().contains("must never silently degrade"), thrown.getMessage());

            var thrownNumeric = assertThrows(UnsupportedOperationException.class,
                    () -> unimplemented.storeIfFieldEquals(null, "version", 3L));
            assertTrue(thrownNumeric.getMessage().contains("must never silently degrade"), thrownNumeric.getMessage());
        }
    }

    @Nested
    @DisplayName("both backends implement both overloads")
    class BackendParity {

        /**
         * A backend that inherited the default would throw at the first conditional
         * write — in production, on a code path exercised only under concurrency.
         */
        @Test
        @DisplayName("neither backend inherits the throwing default")
        void bothBackendsOverrideBothOverloads() throws Exception {
            for (String backend : BACKENDS) {
                Class<?> type = Class.forName(backend);

                Method stringOverload = type.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class, String.class);
                Method longOverload = type.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class, long.class);

                assertEquals(type, stringOverload.getDeclaringClass(),
                        backend + " must declare the String overload — inheriting the default throws at the first conditional write");
                assertEquals(type, longOverload.getDeclaringClass(),
                        backend + " must declare the long overload; the two backends disagree about text-comparing numbers, "
                                + "which is the whole reason it exists");
            }
        }

        /**
         * Both overloads must be able to report the deleted-vs-mismatch distinction:
         * callers map {@code ResourceNotFoundException} to 404 and
         * {@code ResourceModifiedException} to 409, and
         * {@code GroupConversationStore.updateIfState} converts the former into
         * {@code GroupConversationGoneException} so a deletion is never reported as a
         * state conflict.
         */
        @Test
        @DisplayName("both backends declare both CAS outcomes")
        void bothBackendsDeclareBothOutcomes() throws Exception {
            for (String backend : BACKENDS) {
                Class<?> type = Class.forName(backend);
                for (Method method : new Method[]{
                        type.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class, String.class),
                        type.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class, long.class)}) {

                    Set<Class<?>> declared = Set.of(method.getExceptionTypes());
                    assertTrue(declared.contains(IResourceStore.ResourceModifiedException.class),
                            backend + "." + method.getName() + " must declare ResourceModifiedException (the 409 case)");
                    assertTrue(declared.contains(IResourceStore.ResourceNotFoundException.class),
                            backend + "." + method.getName() + " must declare ResourceNotFoundException (the 404 case) — "
                                    + "conflating it with a mismatch reports a deleted discussion as a state conflict");
                }
            }
        }
    }

    @Test
    @DisplayName("the contract is documented on the interface, not only in the implementations")
    void contractIsOnTheInterface() {
        assertDoesNotThrow(() -> IResourceStorage.class.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class,
                String.class));
        assertDoesNotThrow(
                () -> IResourceStorage.class.getMethod("storeIfFieldEquals", IResourceStorage.IResource.class, String.class, long.class));
    }

}
