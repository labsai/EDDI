/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Configuration references resolved after templating — vault, legacy vault,
 * connection, global variable — survive the real Qute engine verbatim. Every
 * {@code ApiCallExecutor} suite stubs templating out, which is how all four
 * shipped failing with "No namespace resolver found".
 */
class ConfigReferenceNamespaceResolversTest {

    private ITemplatingEngine engine;

    @BeforeEach
    void setUp() {
        // Mirrors production: Quarkus registers every NamespaceResolver bean.
        engine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.LegacyVault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Connection())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ${vault:api-key}", "Bearer ${vault:acme/api-key}", "Bearer ${eddivault:api-key}", "${connection:jira}",
            "https://${vars:graphqlHost}/graphql", "Bearer ${caller:token}"})
    @DisplayName("each configuration reference survives templating verbatim")
    void passesThrough(String template) throws Exception {
        assertEquals(template, engine.processTemplate(template, Map.of()));
    }

    @Test
    @DisplayName("a reference alongside ordinary expressions still templates the expressions")
    void coexistsWithExpressions() throws Exception {
        var data = Map.<String, Object>of("properties", Map.of("tenant", "acme"));
        assertEquals("{\"tenant\":\"acme\",\"key\":\"${vault:api-key}\"}",
                engine.processTemplate("{\"tenant\":\"{properties.tenant}\",\"key\":\"${vault:api-key}\"}", data));
    }

    @Test
    @DisplayName("the resolver emits the placeholder, never a value from the data model")
    void neverEmitsAValue() throws Exception {
        var data = Map.<String, Object>of("vault", Map.of("api-key", "leaked-secret"));
        String result = engine.processTemplate("Bearer ${vault:api-key}", data);
        assertEquals("Bearer ${vault:api-key}", result);
        assertFalse(result.contains("leaked-secret"));
    }

    @Test
    @DisplayName("{vars.name} data access is unaffected by the vars namespace")
    void varsDataAccessUnaffected() throws Exception {
        var data = Map.<String, Object>of("vars", Map.of("model", "gpt"));
        assertEquals("gpt", engine.processTemplate("{vars.model}", data));
    }
}
