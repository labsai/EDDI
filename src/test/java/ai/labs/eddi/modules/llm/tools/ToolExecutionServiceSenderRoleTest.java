/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.security.EscalationGuard;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tool ceiling of an email sender role (email plan v3 §9) at the one
 * boundary every tool source passes, and the escalation guard for the roles'
 * own edit tool (§5.3).
 */
@DisplayName("ToolExecutionService — email sender roles")
class ToolExecutionServiceSenderRoleTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private ToolExecutionService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        service = new ToolExecutionService();
        ToolRateLimiter rateLimiter = mock(ToolRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        when(rateLimiter.tryAcquire(any(), anyString(), anyInt())).thenReturn(true);
        set("cacheService", mock(ToolCacheService.class));
        set("rateLimiter", rateLimiter);
        set("costTracker", mock(ToolCostTracker.class));
        set("meterRegistry", meterRegistry);
        service.init();
    }

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    private void set(String name, Object value) throws Exception {
        Field field = ToolExecutionService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private String call(String tool, AtomicInteger runs) {
        return service.executeToolWrapped(ToolInvocation.of(tool), "{}", null, "conv-1", () -> {
            runs.incrementAndGet();
            return "ran";
        }, false, false, false, 60, ToolExecutionService.TIMEOUT_DISABLED);
    }

    @Test
    @DisplayName("a turn under a role's ceiling runs the named tool and nothing else")
    void ceilingAllowsOnlyNamedTools() {
        principals.bind(new ResolutionPrincipal("email:x:ann@customer-a.com", Provenance.SELF_ASSERTED, TurnOrigin.EMAIL, Set.of("kb_search")));
        var runs = new AtomicInteger();

        assertEquals("ran", call("kb_search", runs));
        String refused = call("http_post", runs);

        assertEquals(1, runs.get());
        assertTrue(refused.startsWith("Error: Refused"), refused);
        assertEquals(1.0, meterRegistry.counter("eddi.tool.execution.sender_role_refused", "tool", "http_post").count());
    }

    @Test
    @DisplayName("an empty ceiling (an unauthenticated sender) runs no tool at all")
    void emptyCeilingRunsNothing() {
        principals.bind(new ResolutionPrincipal("email-anon:1", Provenance.SELF_ASSERTED, TurnOrigin.EMAIL, Set.of()));
        var runs = new AtomicInteger();

        assertTrue(call("kb_search", runs).startsWith("Error: Refused"));
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("without a ceiling nothing changes")
    void noCeiling() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, null));
        var runs = new AtomicInteger();

        assertEquals("ran", call("http_post", runs));
    }

    @Test
    @DisplayName("a tainted turn can never edit the sender roles, even when its role names the tool")
    void taintedTurnCannotEditRoles() {
        principals.bind(new ResolutionPrincipal("email:x:cfo@company.com", Provenance.SELF_ASSERTED, TurnOrigin.EMAIL,
                Set.of("update_email_roles")));
        var runs = new AtomicInteger();

        String refused = call("update_email_roles", runs);

        assertEquals(0, runs.get());
        assertTrue(refused.startsWith("Error: Refused"), refused);
        assertTrue(EscalationGuard.isGuardedTool("eddi_update_email_roles"), "behind an MCP namespace prefix too");
        assertTrue(EscalationGuard.isGuardedRequest("PUT", "/channelstore/channels/aabbccddee1122334455aabb/email-roles"));
    }
}
