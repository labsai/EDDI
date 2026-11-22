/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.hitl.tools.ApprovedCallScope;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The authoritative runtime refusal of email plan v3 §5.3: in a turn a mail
 * started, a guarded tool never runs — not through the LLM overload, not
 * through the {@code mcpcalls} one — and the refusal comes before the rate
 * limiter and the cache.
 */
@DisplayName("ToolExecutionService — the email escalation guard")
class ToolExecutionServiceEscalationGuardTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private ToolExecutionService service;
    private ToolRateLimiter rateLimiter;
    private ToolCacheService cacheService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        service = new ToolExecutionService();
        rateLimiter = mock(ToolRateLimiter.class);
        cacheService = mock(ToolCacheService.class);
        meterRegistry = new SimpleMeterRegistry();
        when(rateLimiter.tryAcquire(any(), anyString(), anyInt())).thenReturn(true);
        set("cacheService", cacheService);
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

    @Test
    @DisplayName("a tainted turn's update_email_senders never runs, and is refused before the rate limiter and the cache")
    void taintedTurnIsRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        var runs = new AtomicInteger();

        String llm = service.executeToolWrapped(ToolInvocation.of("update_email_senders"), "{}", "u:scope", "conv-1", () -> {
            runs.incrementAndGet();
            return "updated";
        }, true, true, true, 60, ToolExecutionService.TIMEOUT_DISABLED);
        String mcpCalls = service.executeToolWrapped("update_channel_integration", "{}", null, "conv-1", () -> {
            runs.incrementAndGet();
            return "updated";
        }, true, false, true, 60);

        assertEquals(0, runs.get());
        assertTrue(llm.startsWith("Error: Refused"), llm);
        assertTrue(mcpCalls.startsWith("Error: Refused"), mcpCalls);
        verify(rateLimiter, never()).tryAcquire(any(), anyString(), anyInt());
        verify(cacheService, never()).get(any(), anyString(), any());
        assertEquals(1.0, meterRegistry.counter("eddi.tool.execution.escalation_refused", "tool", "update_email_senders").count());
    }

    /**
     * A tool of conversation B that runs inside the approved execution of a call of
     * conversation A (a nested agent call, on the same thread) must not state A's
     * approval on its requests; A's own tool does, on the worker thread too.
     */
    @Test
    @DisplayName("a tool states an approval only when the approved call is its own conversation's, on the worker thread too")
    void approvalIsScopedToTheToolsConversation() {
        var protection = SelfUngatingGuard.Protection.ofAgent("agent-1");
        for (int timeoutMs : new int[]{ToolExecutionService.TIMEOUT_DISABLED, 5_000}) {
            String own = ApprovedCallScope.run(protection, "conv-a", () -> service.executeToolWrapped(ToolInvocation.of("calculator"), "{}", null,
                    "conv-a", () -> String.valueOf(ApprovedCallScope.approvesHere()), false, false, false, 60, timeoutMs));
            String nested = ApprovedCallScope.run(protection, "conv-a", () -> service.executeToolWrapped(ToolInvocation.of("calculator"), "{}",
                    null, "conv-b", () -> String.valueOf(ApprovedCallScope.approvesHere()), false, false, false, 60, timeoutMs));
            assertEquals("true", own, "timeout " + timeoutMs);
            assertEquals("false", nested, "timeout " + timeoutMs);
        }
    }

    @Test
    @DisplayName("the same tool runs in an untainted turn, and an unguarded tool runs in a tainted one")
    void othersRun() {
        assertEquals("updated", service.executeToolWrapped("update_email_senders", "{}", null, "conv-1", () -> "updated", false, false,
                false, 60));
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        assertEquals("42", service.executeToolWrapped("calculator", "{}", null, "conv-1", () -> "42", false, false, false, 60));
    }
}
