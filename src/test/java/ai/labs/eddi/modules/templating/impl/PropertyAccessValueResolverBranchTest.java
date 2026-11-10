/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which members of a template data object the restricted property resolver
 * reads — getters, record components and public fields only — and how a failing
 * getter surfaces.
 */
class PropertyAccessValueResolverBranchTest {

    private final PropertyAccessValueResolver resolver = new PropertyAccessValueResolver();

    public record Entry(String speaker, int turn) {
    }

    public static class Bean {
        public String title = "t";
        public static String SHARED = "s";
        public Class<?> type = String.class;
        public final Map<String, String> labels = Map.of("k", "v");

        public String getName() {
            return "n";
        }

        public boolean isActive() {
            return true;
        }

        public Boolean hasChildren() {
            return Boolean.FALSE;
        }

        public String isNotABoolean() {
            return "x";
        }

        public void getNothing() {
        }

        public String getWith(String argument) {
            return argument;
        }

        public static String getStatic() {
            return "static";
        }

        public String getFailing() {
            throw new IllegalStateException("broken getter");
        }

        public String getErroring() {
            throw new AssertionError("an error, not an exception");
        }

        public ClassLoader getLoader() {
            return getClass().getClassLoader();
        }

        public String clear() {
            return "not a getter";
        }
    }

    static class Hidden {
        public String visible = "v";
    }

    private Object read(Object base, String name) throws Exception {
        return resolver.accessorFor(base.getClass(), name).orElseThrow().read(base);
    }

    private boolean readable(Object base, String name) {
        return resolver.accessorFor(base.getClass(), name).isPresent();
    }

    @Test
    void recordComponentsAreRead() throws Exception {
        assertEquals("Ada", read(new Entry("Ada", 2), "speaker"));
        assertEquals(2, read(new Entry("Ada", 2), "turn"));
        assertFalse(readable(new Entry("Ada", 2), "nobody"));
    }

    @Test
    void gettersAreReadByPropertyOrByTheirOwnName() throws Exception {
        var bean = new Bean();
        assertEquals("n", read(bean, "name"));
        assertEquals("n", read(bean, "getName"));
        assertEquals(true, read(bean, "active"));
        assertEquals(Boolean.FALSE, read(bean, "children"));
    }

    @Test
    void nonGettersAreNeverInvoked() {
        var bean = new Bean();
        assertFalse(readable(bean, "notABoolean"), "isX must return a boolean");
        assertFalse(readable(bean, "nothing"), "a void method is not a getter");
        assertFalse(readable(bean, "with"), "a getter that takes arguments is not a property");
        assertFalse(readable(bean, "static"), "a static method is not a property");
        assertFalse(readable(bean, "clear"), "a no-argument method that is not getter-shaped");
        assertFalse(readable(bean, "loader"), "a class loader is never handed to a template");
        assertFalse(readable(new AtomicInteger(), "andIncrement"), "getAndIncrement mutates");
    }

    @Test
    void publicInstanceFieldsAreReadButNotStaticOrBlockedOnes() throws Exception {
        var bean = new Bean();
        assertEquals("t", read(bean, "title"));
        assertEquals(Map.of("k", "v"), read(bean, "labels"));
        assertFalse(readable(bean, "SHARED"));
        assertFalse(readable(bean, "type"), "a Class-typed field is blocked");
        assertFalse(readable(new Hidden(), "visible"), "a field of a non-public class is not opened");
    }

    @Test
    void blockedNamesAndTypesAreNeverBases() {
        assertFalse(readable(new Bean(), "class"));
        assertFalse(readable(new Bean(), ""));
        assertFalse(resolver.accessorFor(Bean.class, null).isPresent());
        assertTrue(PropertyAccessValueResolver.isBlocked(Thread.class));
        assertTrue(PropertyAccessValueResolver.isBlocked(Method.class));
        assertFalse(PropertyAccessValueResolver.isBlocked(String.class));
    }

    @Test
    void aFailingGetterSurfacesItsCause() {
        var failing = assertThrows(ReflectiveOperationException.class, () -> read(new Bean(), "failing"));
        assertEquals("broken getter", failing.getCause().getMessage());
        var erroring = assertThrows(ReflectiveOperationException.class, () -> read(new Bean(), "erroring"));
        assertInstanceOf(AssertionError.class, erroring.getCause(), "an Error is not wrapped as an exception cause");
    }

    @Test
    void lookupsAreCached() {
        var first = resolver.accessorFor(Bean.class, "name");
        assertSame(first, resolver.accessorFor(Bean.class, "name"), "the same accessor instance comes from the cache");
    }

    @Test
    void theResolverIsConsultedLast() {
        assertEquals(PropertyAccessValueResolver.PRIORITY, resolver.getPriority());
    }
}
