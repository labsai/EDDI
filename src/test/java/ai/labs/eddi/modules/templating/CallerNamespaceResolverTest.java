/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The templating step sits in front of the caller-identity resolver, so a
 * {@code ${caller:...}} reference has to survive it intact.
 * <p>
 * This is the test whose absence let a non-functional feature ship: every
 * {@code ApiCallExecutor} suite stubs templating out as a pass-through, so
 * nothing exercised header text through the real Qute engine.
 *
 * @author ginccc
 */
class CallerNamespaceResolverTest {

    private ITemplatingEngine engine;
    private ITemplatingEngine engineWithoutResolver;

    @BeforeEach
    void setUp() {
        // Mirrors the production engine: Quarkus discovers NamespaceResolver beans
        // and adds them to the Engine that TemplatingEngine is injected with.
        engine = new TemplatingEngine(
                Engine.builder().addDefaults().strictRendering(false).addNamespaceResolver(new CallerNamespaceResolver()).build());
        engineWithoutResolver = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
    }

    @Test
    @DisplayName("a caller-token header survives templating verbatim")
    void passesCallerTokenThrough() throws Exception {
        assertEquals("Bearer ${caller:token}", engine.processTemplate("Bearer ${caller:token}", Map.of()));
    }

    @Test
    void passesCallerUserIdThrough() throws Exception {
        assertEquals("${caller:userId}", engine.processTemplate("${caller:userId}", Map.of()));
    }

    @Test
    @DisplayName("a caller reference alongside a normal property still templates the property")
    void coexistsWithOrdinaryExpressions() throws Exception {
        var data = Map.<String, Object>of("properties", Map.of("tenant", "acme"));
        assertEquals("acme/${caller:token}", engine.processTemplate("{properties.tenant}/${caller:token}", data));
    }

    @Test
    @DisplayName("the resolver emits a placeholder, never a value")
    void neverEmitsAValue() throws Exception {
        // Even with a 'caller' key in the data model, the namespace wins and the
        // placeholder is returned — the token can only come from the real resolver.
        var data = Map.<String, Object>of("caller", Map.of("token", "leaked-secret"));
        String result = engine.processTemplate("Bearer ${caller:token}", data);
        assertEquals("Bearer ${caller:token}", result);
        assertTrue(!result.contains("leaked-secret"));
    }

    // ==================== Guard rails ====================

    @Test
    @DisplayName("without the resolver the reference is a hard failure — the bug this fixes")
    void failsWithoutTheResolver() {
        var e = assertThrows(ITemplatingEngine.TemplateEngineException.class,
                () -> engineWithoutResolver.processTemplate("Bearer ${caller:token}", Map.of()));
        assertTrue(e.getMessage().contains("No namespace resolver found for [caller]"), e.getMessage());
    }

    @Test
    @DisplayName("vault references deliberately still fail in templated positions")
    void vaultIsDeliberatelyNotPassedThrough() {
        // Letting ${vault:...} survive templating would widen where a secret can be
        // substituted — notably into a request body, which is persisted to
        // conversation memory unscrubbed. Keep it failing loudly.
        var e = assertThrows(ITemplatingEngine.TemplateEngineException.class,
                () -> engine.processTemplate("Bearer ${vault:my-key}", Map.of()));
        assertTrue(e.getMessage().contains("No namespace resolver found for [vault]"), e.getMessage());
    }
}
