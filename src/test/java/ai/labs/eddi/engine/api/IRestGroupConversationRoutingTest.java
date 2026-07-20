/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the JAX-RS routing contract of {@link IRestGroupConversation}.
 * <p>
 * This exists because of a real regression: the class-level {@code @Path} was
 * flattened from {@code /groups/{groupId}/conversations} to {@code /groups} (so
 * the cross-group approvals inbox could live alongside the per-group routes),
 * and every method was supposed to carry the {@code /{groupId}/conversations}
 * prefix itself. Four methods kept their old class-relative paths, so they lost
 * the {@code {groupId}} template segment while still declaring
 * {@code @PathParam("groupId")} — RESTEasy bound {@code groupId} to
 * {@code null} and every follow-up / continue / close request would have 404'd
 * in production.
 * <p>
 * The unit tests could not see this: they invoke the resource methods directly
 * and never exercise JAX-RS path binding. These assertions check the invariant
 * on the annotations themselves, which is exactly what a direct-invocation test
 * cannot.
 */
class IRestGroupConversationRoutingTest {

    private static String classPath() {
        Path p = IRestGroupConversation.class.getAnnotation(Path.class);
        return p == null ? "" : p.value();
    }

    /**
     * The full external path of a resource method: class-level @Path +
     * method @Path.
     */
    private static String effectivePath(Method m) {
        Path p = m.getAnnotation(Path.class);
        String methodPath = p == null ? "" : p.value();
        return (classPath() + "/" + methodPath).replace("//", "/");
    }

    private static List<Method> resourceMethods() {
        var out = new ArrayList<Method>();
        for (Method m : IRestGroupConversation.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    @Test
    @DisplayName("every @PathParam a method declares must exist as a template in its effective path")
    void everyPathParamHasATemplateSegment() {
        var violations = new ArrayList<String>();

        for (Method m : resourceMethods()) {
            String path = effectivePath(m);
            for (Parameter param : m.getParameters()) {
                PathParam pp = param.getAnnotation(PathParam.class);
                if (pp == null) {
                    continue;
                }
                String template = "{" + pp.value() + "}";
                if (!path.contains(template)) {
                    violations.add(m.getName() + " declares @PathParam(\"" + pp.value()
                            + "\") but its path '" + path + "' has no " + template + " segment");
                }
            }
        }

        // A @PathParam with no matching template binds to null at runtime — the exact
        // failure that made followup/continue/continue-stream/close 404 for every
        // caller.
        assertTrue(violations.isEmpty(),
                "JAX-RS path/param mismatch (the parameter would bind to null):\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("every per-conversation route is nested under /groups/{groupId}/conversations/{groupConversationId}")
    void perConversationRoutesKeepTheGroupPrefix() {
        var violations = new ArrayList<String>();

        for (Method m : resourceMethods()) {
            boolean takesConversationId = false;
            for (Parameter param : m.getParameters()) {
                PathParam pp = param.getAnnotation(PathParam.class);
                if (pp != null && "groupConversationId".equals(pp.value())) {
                    takesConversationId = true;
                }
            }
            if (!takesConversationId) {
                continue;
            }
            String path = effectivePath(m);
            if (!path.startsWith("/groups/{groupId}/conversations/{groupConversationId}")) {
                violations.add(m.getName() + " -> " + path);
            }
        }

        assertTrue(violations.isEmpty(),
                "per-conversation routes must stay under /groups/{groupId}/conversations/{groupConversationId} "
                        + "(external URLs must not change):\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("the four post-discussion endpoints resolve to their documented external URLs")
    void postDiscussionEndpointsHaveTheirDocumentedUrls() throws Exception {
        String base = "/groups/{groupId}/conversations/{groupConversationId}";

        assertPath("followUpWithMember", base + "/followup");
        assertPath("continueDiscussion", base + "/continue");
        assertPath("continueDiscussionStreaming", base + "/continue/stream");
        assertPath("closeGroupConversation", base + "/close");
    }

    private static void assertPath(String methodName, String expected) {
        Method target = null;
        for (Method m : resourceMethods()) {
            if (m.getName().equals(methodName)) {
                target = m;
                break;
            }
        }
        assertFalse(target == null, "no such resource method: " + methodName);
        String actual = effectivePath(target);
        assertTrue(expected.equals(actual),
                methodName + " must be exposed at " + expected + " but is at " + actual);
    }
}
