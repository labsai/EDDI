/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.configs.agents.IRestCapabilityRegistry;
import ai.labs.eddi.configs.dictionary.IRestAction;
import ai.labs.eddi.configs.dictionary.IRestExpression;
import ai.labs.eddi.configs.output.keys.IRestOutputActions;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStepStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A8 — every addressable configuration surface must carry the standard
 * authoring role gate.
 * <p>
 * These stores were the set that had a {@code @Path} but no
 * {@code @RolesAllowed}, which left a chat-only account able to read (and, for
 * the parser store, rewrite) the configuration every deployed agent depends on.
 * The assertions are deliberately exact: a weaker or missing role set fails the
 * build rather than silently reopening the hole.
 */
@DisplayName("Configuration stores carry the authoring role gate")
class ConfigStoreRoleGateTest {

    private static final List<String> AUTHORING_ROLES = List.of("eddi-admin", "eddi-editor");

    static Stream<Object[]> guardedConfigStores() {
        return Stream.of(
                new Object[]{IRestParserStore.class, "/parserstore/parsers"},
                new Object[]{IRestAction.class, "/actions"},
                new Object[]{IRestExpression.class, "/expressions"},
                new Object[]{IRestOutputActions.class, "/outputstore/actions"},
                new Object[]{IRestWorkflowStepStore.class, "/extensionstore/extensions"},
                new Object[]{IRestCapabilityRegistry.class, "/capabilities"});
    }

    @ParameterizedTest(name = "{1} is gated to eddi-admin/eddi-editor")
    @MethodSource("guardedConfigStores")
    void configStoreIsGatedToAuthoringRoles(Class<?> restInterface, String expectedPath) {
        Path path = restInterface.getAnnotation(Path.class);
        assertNotNull(path, restInterface.getSimpleName() + " is expected to be an addressable JAX-RS resource");
        assertEquals(expectedPath, path.value(),
                "Path drifted — this test guards the endpoint at " + expectedPath);

        RolesAllowed roles = restInterface.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, expectedPath + " must not be reachable by any authenticated account; "
                + "it exposes (and for some stores rewrites) shared agent configuration");
        assertEquals(AUTHORING_ROLES, List.of(roles.value()),
                expectedPath + " must use the same role set as every sibling configuration store");
    }

    /**
     * {@link IRestVersionInfo} is a mixin, not a resource: it has no {@code @Path},
     * so annotating it would guard nothing that its {@code @Path}-bearing
     * sub-interfaces do not already guard at class level, while attaching a
     * security annotation to the declaring type of default methods that the non-CDI
     * {@code RestVersionInfo} helper calls in-process during config resolution and
     * ZIP import. This test pins that reasoning: if someone gives it a
     * {@code @Path}, the "it is only a mixin" argument no longer holds and the gate
     * has to be reconsidered.
     */
    @Test
    @DisplayName("IRestVersionInfo stays a path-less mixin, so it needs no role of its own")
    void versionInfoMixinIsNotAnAddressableResource() {
        assertNull(IRestVersionInfo.class.getAnnotation(Path.class),
                "IRestVersionInfo gained a @Path — it is now addressable and must carry its own role gate");
    }
}
