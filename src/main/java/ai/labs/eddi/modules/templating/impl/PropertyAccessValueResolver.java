/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import io.quarkus.qute.CompletedStage;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.Results;
import io.quarkus.qute.ValueResolver;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime-template replacement for Qute's
 * {@link io.quarkus.qute.ReflectionValueResolver}.
 * <p>
 * It reads <em>properties</em> of the objects in the template data model and
 * nothing else:
 * <ul>
 * <li>a record component — {@code {entry.speaker}};</li>
 * <li>a public no-argument getter — {@code {item.text}} calls
 * {@code getText()}, and {@code isX()}/{@code hasX()} for booleans; the getter
 * may also be named directly, {@code {item.getText}};</li>
 * <li>a public instance field.</li>
 * </ul>
 * Unlike the stock resolver it never invokes a method that takes arguments, and
 * it never invokes a no-argument method that is not a getter — so
 * {@code {list.clear}} cannot mutate the data model and a string cannot be
 * {@code repeat}ed into gigabytes. The string, collection and map operations
 * templates legitimately use are provided by dedicated resolvers (EDDI's
 * {@code StringTemplateExtensions}, Qute's collection and map resolvers), which
 * is also how they work in a native image where no reflection is available.
 * <p>
 * Reflection-sensitive types are never a base: {@link Class}, class loaders,
 * modules, threads, the runtime, processes and anything under
 * {@code java.lang.reflect}, {@code java.lang.invoke} or {@code java.security}.
 */
final class PropertyAccessValueResolver implements ValueResolver {

    /** Same as the reflection resolver it replaces: consulted last. */
    static final int PRIORITY = -1;

    private static final int MAX_CACHED_MEMBERS = 10_000;

    private static final Set<String> BLOCKED_NAMES = Set.of("class", "getClass", "classLoader", "getClassLoader", "declaringClass",
            "getDeclaringClass", "module", "getModule");

    private static final List<Class<?>> BLOCKED_TYPES = List.of(Class.class, ClassLoader.class, Module.class, ModuleLayer.class, Thread.class,
            ThreadGroup.class, Runtime.class, Process.class, ProcessHandle.class, System.class);

    private static final List<String> BLOCKED_PACKAGE_PREFIXES = List.of("java.lang.reflect.", "java.lang.invoke.", "java.security.",
            "sun.", "jdk.internal.", "com.sun.",
            // Qute's own objects (e.g. the not-found marker) and framework internals
            "io.quarkus.", "io.smallrye.", "org.jboss.");

    private final Map<MemberKey, Optional<Accessor>> accessors = new ConcurrentHashMap<>();

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean appliesTo(EvalContext context) {
        Object base = context.getBase();
        if (base == null || !context.getParams().isEmpty()) {
            return false;
        }
        return accessorFor(base.getClass(), context.getName()).isPresent();
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext context) {
        Object base = context.getBase();
        Optional<Accessor> accessor = base == null ? Optional.empty() : accessorFor(base.getClass(), context.getName());
        if (accessor.isEmpty() || !context.getParams().isEmpty()) {
            return Results.notFound(context);
        }
        try {
            return CompletedStage.of(accessor.get().read(base));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    Optional<Accessor> accessorFor(Class<?> type, String name) {
        if (name == null || name.isEmpty() || BLOCKED_NAMES.contains(name) || isBlocked(type)) {
            return Optional.empty();
        }
        MemberKey key = new MemberKey(type, name);
        Optional<Accessor> cached = accessors.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Accessor> found = findAccessor(type, name);
        if (accessors.size() < MAX_CACHED_MEMBERS) {
            accessors.put(key, found);
        }
        return found;
    }

    static boolean isBlocked(Class<?> type) {
        for (Class<?> blocked : BLOCKED_TYPES) {
            if (blocked.isAssignableFrom(type)) {
                return true;
            }
        }
        String name = type.getName();
        for (String prefix : BLOCKED_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Accessor> findAccessor(Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    Method method = component.getAccessor();
                    if (method.trySetAccessible()) {
                        return Optional.of(new MethodAccessor(method));
                    }
                }
            }
        }

        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of(name, "get" + capitalized, "is" + capitalized, "has" + capitalized)) {
            if (!isGetterName(candidate)) {
                continue;
            }
            Method method = findPublicNoArgMethod(type, candidate);
            if (method != null && isGetter(method)) {
                return Optional.of(new MethodAccessor(method));
            }
        }

        try {
            Field field = type.getField(name);
            if (!Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getDeclaringClass().getModifiers())
                    && !isBlocked(field.getType())) {
                return Optional.of(new FieldAccessor(field));
            }
        } catch (NoSuchFieldException | SecurityException ignored) {
            // no such field
        }
        return Optional.empty();
    }

    private static boolean isGetterName(String name) {
        return hasPrefix(name, "get") || hasPrefix(name, "is") || hasPrefix(name, "has");
    }

    private static boolean hasPrefix(String name, String prefix) {
        return name.length() > prefix.length() && name.startsWith(prefix) && Character.isUpperCase(name.charAt(prefix.length()));
    }

    private static boolean isGetter(Method method) {
        if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0 || method.getReturnType() == void.class
                || BLOCKED_NAMES.contains(method.getName()) || isBlocked(method.getReturnType())) {
            return false;
        }
        String name = method.getName();
        if (name.startsWith("is") || name.startsWith("has")) {
            return method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class;
        }
        return true;
    }

    /**
     * Finds the method in a PUBLIC type of the hierarchy, so it can be invoked
     * without opening the declaring class — the implementation class of e.g.
     * {@code Map.of(...)} is not public, but {@code Map} is.
     */
    private static Method findPublicNoArgMethod(Class<?> type, String name) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (Modifier.isPublic(current.getModifiers())) {
                try {
                    Method method = current.getMethod(name);
                    if (Modifier.isPublic(method.getDeclaringClass().getModifiers()) || method.trySetAccessible()) {
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {
                    // try the supertypes
                }
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            queue.addAll(List.of(current.getInterfaces()));
        }
        return null;
    }

    private record MemberKey(Class<?> type, String name) {
    }

    interface Accessor {
        Object read(Object base) throws ReflectiveOperationException;
    }

    private record MethodAccessor(Method method) implements Accessor {
        @Override
        public Object read(Object base) throws ReflectiveOperationException {
            try {
                return method.invoke(base);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception cause ? new ReflectiveOperationException(cause) : e;
            }
        }
    }

    private record FieldAccessor(Field field) implements Accessor {
        @Override
        public Object read(Object base) throws ReflectiveOperationException {
            return field.get(base);
        }
    }
}
