/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.a2a.RestA2AEndpoint;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7 — every JAX-RS resource method says who may call it.
 * <p>
 * {@code quarkus.security.jaxrs.deny-unannotated-endpoints=true} refuses a
 * resource method that carries no security annotation — on itself, on the
 * interface method it implements, or on the class or interface declaring the
 * resource. That is the safe default, but it is also an outage waiting to
 * happen: a public endpoint (an SPA shell, the OAuth callback, a webhook) that
 * forgot its {@code @PermitAll} is silently denied to everybody. This test
 * makes the omission a build failure instead, by walking every compiled class.
 */
@DisplayName("F7: every JAX-RS endpoint carries a security annotation")
class EndpointSecurityAnnotationsTest {

    private static final List<Class<? extends Annotation>> SECURITY = List.of(RolesAllowed.class, PermitAll.class, Authenticated.class,
            DenyAll.class);

    private static final List<Class<? extends Annotation>> HTTP = List.of(GET.class, POST.class, PUT.class, DELETE.class, PATCH.class,
            HEAD.class, OPTIONS.class);

    @Test
    @DisplayName("deny-unannotated-endpoints is on")
    void denyUnannotatedIsConfigured() throws IOException {
        // From source, not the classpath: src/test/resources has its own
        // application.properties, which shadows the production one.
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get("src", "main", "resources", "application.properties"))) {
            properties.load(in);
        }
        assertEquals("true", properties.getProperty("quarkus.security.jaxrs.deny-unannotated-endpoints"),
                "without it an unannotated endpoint is reachable by any token the catch-all admits");
    }

    @Test
    @DisplayName("no resource method is left to the default")
    void everyResourceMethodIsAnnotated() throws Exception {
        List<String> unannotated = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : mainClasses()) {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || !isResource(type)) {
                continue;
            }
            for (Method method : type.getMethods()) {
                if (!isResourceMethod(type, method)) {
                    continue;
                }
                checked++;
                if (!isSecured(type, method)) {
                    unannotated.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertTrue(checked > 300, "the scan found only " + checked + " resource methods — it is not seeing the application");
        assertEquals(List.of(), unannotated, "these endpoints carry no security annotation and would be denied to everyone "
                + "(deny-unannotated-endpoints): annotate each with the roles it needs, or @PermitAll if it is public");
    }

    // ── helpers ──

    private static List<Class<?>> mainClasses() throws URISyntaxException, IOException {
        var root = Paths.get(RestA2AEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> classes = new ArrayList<>();
        List<String> unloadableResources = new ArrayList<>();
        // `var`, not the type: java.nio.file.Path would collide with the JAX-RS @Path.
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".class") && !f.getFileName().toString().contains("$")).toList()) {
                String name = root.relativize(file).toString().replace('\\', '.').replace('/', '.');
                name = name.substring(0, name.length() - ".class".length());
                if (!name.startsWith("ai.labs.eddi.")) {
                    continue;
                }
                try {
                    classes.add(Class.forName(name, false, EndpointSecurityAnnotationsTest.class.getClassLoader()));
                } catch (Throwable notLoadable) {
                    // Not loadable here. Only a problem if it is a resource: then this test
                    // would pass without ever having looked at it.
                    if (new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("Ljakarta/ws/rs/Path;")) {
                        unloadableResources.add(name + " (" + notLoadable + ")");
                    }
                }
            }
        }
        assertEquals(List.of(), unloadableResources, "these JAX-RS classes could not be loaded, so their endpoints went unchecked");
        return classes;
    }

    /** The class and every interface it implements, transitively. */
    private static Set<Class<?>> hierarchy(Class<?> type) {
        Set<Class<?>> all = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            all.add(c);
            addInterfaces(c, all);
        }
        return all;
    }

    private static void addInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> i : type.getInterfaces()) {
            if (into.add(i)) {
                addInterfaces(i, into);
            }
        }
    }

    private static boolean isResource(Class<?> type) {
        return hierarchy(type).stream().anyMatch(c -> c.isAnnotationPresent(Path.class));
    }

    /** The same method as declared anywhere in the hierarchy. */
    private static List<Method> declarations(Class<?> type, Method method) {
        List<Method> found = new ArrayList<>();
        for (Class<?> c : hierarchy(type)) {
            try {
                found.add(c.getDeclaredMethod(method.getName(), method.getParameterTypes()));
            } catch (NoSuchMethodException e) {
                // not declared here
            }
        }
        return found;
    }

    private static boolean isResourceMethod(Class<?> type, Method method) {
        return declarations(type, method).stream().anyMatch(m -> HTTP.stream().anyMatch(m::isAnnotationPresent));
    }

    private static boolean isSecured(Class<?> type, Method method) {
        for (Method declaration : declarations(type, method)) {
            if (SECURITY.stream().anyMatch(declaration::isAnnotationPresent)) {
                return true;
            }
        }
        // Class-level: only a type that DECLARES the method — the implementation class
        // when it overrides it, the interface that carries it. Not any @Path interface
        // in the hierarchy, and not the implementation class when the method is a
        // default it merely inherits: Quarkus reads a class-level annotation from the
        // declaring type, so a default method inherited from an unannotated mixin
        // (IRestVersionInfo) is denied even when the store interface — or the
        // implementation class — carries @RolesAllowed. The live 403 this test was
        // corrected after.
        for (Method declaration : declarations(type, method)) {
            if (SECURITY.stream().anyMatch(declaration.getDeclaringClass()::isAnnotationPresent)) {
                return true;
            }
        }
        return false;
    }
}
