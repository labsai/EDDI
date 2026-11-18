/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The team-subject snapshot {@link CallerIdentityContext} takes at the request
 * boundary, and where it does and does not travel: onto the tool-execution
 * thread, yes; into a HITL resume from an approver's memberships, never.
 */
class CallerIdentityContextTeamSubjectsTest {

    private static final String GROUPS_CLAIM = "groups";
    private static final String SALES = Subjects.team("/acme/sales");
    private static final String LEGAL = Subjects.team("/acme/legal");
    private static final String FINANCE = Subjects.team("/acme/finance");

    /**
     * A bare context: the binding is a static ThreadLocal, so this is the same one.
     */
    private final CallerIdentityContext context = new CallerIdentityContext(null, null);

    @AfterEach
    void tearDown() {
        context.clear();
    }

    // ==================== capture ====================

    @Test
    @DisplayName("capture() snapshots the token's teams with the same derivation ResourceAccessGuard uses")
    void captureTakesTheTokensTeams() {
        var identity = mockIdentity("alice", "alice-token", List.of("/acme/sales", "/acme/legal"));
        var spaceContext = spaceContextOver(identity);

        var captured = new CallerIdentityContext(identity, null, spaceContext).capture();

        assertNotNull(captured);
        assertEquals(List.of(SALES, LEGAL), captured.teamSubjects());
        assertEquals(CallerSpaces.teamSubjectsOf(spaceContext.current()), captured.teamSubjects(),
                "one derivation of the current token's teams, not two");
        assertFalse(captured.teamSubjects().contains(Subjects.personalSpace("alice")), "the personal space is not a team");
        assertEquals("alice-token", captured.token());
    }

    @Test
    @DisplayName("a caller with no groups claim belongs to no team")
    void noClaimNoTeams() {
        var identity = mockIdentity("alice", "alice-token", null);
        var captured = new CallerIdentityContext(identity, null, spaceContextOver(identity)).capture();
        assertEquals(List.of(), captured.teamSubjects());
    }

    @Test
    @DisplayName("an anonymous request yields no identity, so no teams")
    void anonymousHasNoTeams() {
        var identity = mockIdentity("alice", "alice-token", List.of("/acme/sales"));
        when(identity.isAnonymous()).thenReturn(true);

        assertNull(new CallerIdentityContext(identity, null, spaceContextOver(identity)).capture());
    }

    @Test
    @DisplayName("a groups claim that cannot be read costs the caller their teams, not their token")
    void unreadableTeamsKeepTheToken() {
        var identity = mockIdentity("alice", "alice-token", List.of("/acme/sales"));
        var spaceContext = mock(SpaceContext.class);
        when(spaceContext.current()).thenThrow(new IllegalStateException("no request context"));

        var captured = new CallerIdentityContext(identity, null, spaceContext).capture();

        assertNotNull(captured);
        assertEquals("alice-token", captured.token());
        assertEquals(List.of(), captured.teamSubjects());
    }

    @Test
    @DisplayName("without a SpaceContext (the two-argument test seam) the identity has no teams")
    void noSpaceContextNoTeams() {
        var identity = mockIdentity("alice", "alice-token", List.of("/acme/sales"));
        assertEquals(List.of(), new CallerIdentityContext(identity, null).capture().teamSubjects());
    }

    @Test
    @DisplayName("a token-less run (schedule, cadence) has no identity bound, so no teams reach it")
    void tokenlessRunHasNoTeams() {
        var unauthenticated = new CallerIdentityContext(null, null, null);
        assertNull(unauthenticated.captureOrCurrent(), "no request and nothing bound: nobody, and so no team");

        // A run that is deliberately unauthenticated binds null, even on a thread that
        // a previous turn left a team-carrying identity on.
        context.bind(new CallerIdentity("tok", "alice", "o", Map.of(), false, List.of(SALES)));
        var seen = new AtomicReference<CallerIdentity>(new CallerIdentity("sentinel", "x", "o"));
        context.withIdentity(null, (Runnable) () -> seen.set(context.current())).run();
        assertNull(seen.get());
    }

    // ==================== propagation — the acceptance test ====================

    @Test
    @DisplayName("acceptance: teams captured on the request thread are readable inside a tool call on a virtual thread")
    void teamsReachTheToolThroughTheRealPropagationPath() throws Exception {
        // Captured on the "request" thread through the real capture() and SpaceContext.
        var securityIdentity = mockIdentity("alice", "alice-token", List.of("/acme/sales", "/acme/legal"));
        var requestContext = new CallerIdentityContext(securityIdentity, null, spaceContextOver(securityIdentity));
        CallerIdentity captured = requestContext.capture();
        assertEquals(List.of(SALES, LEGAL), captured.teamSubjects());

        // The pipeline thread binds it for the turn, exactly as ConversationService
        // does.
        requestContext.bind(captured);

        // The tool call goes through ToolExecutionService with a bound, so it runs on
        // the service's virtual-thread timeout executor behind
        // CallerIdentityContext.propagate — the real path a tool call takes.
        var service = toolExecutionServiceWith(requestContext);
        var onVirtualThread = new AtomicBoolean();
        var seenTeams = new AtomicReference<List<String>>();
        var seenThread = new AtomicReference<Thread>();

        String result = service.executeToolWrapped(ToolInvocation.of("teamProbe"), "{}", null, "conv-1", () -> {
            onVirtualThread.set(Thread.currentThread().isVirtual());
            seenThread.set(Thread.currentThread());
            CallerIdentity there = requestContext.current();
            seenTeams.set(there == null ? null : there.teamSubjects());
            return "ok";
        }, false, false, false, 0, 10_000);

        assertEquals("ok", result);
        assertTrue(onVirtualThread.get(), "the tool must run off the pipeline thread, or this test proves nothing");
        assertFalse(Thread.currentThread() == seenThread.get());
        assertEquals(List.of(SALES, LEGAL), seenTeams.get(), "CallerIdentityContext.current().teamSubjects() on the tool thread");
    }

    @Test
    @DisplayName("propagate(Runnable) carries the teams too")
    void runnablePropagationCarriesTeams() throws Exception {
        context.bind(new CallerIdentity("tok", "alice", "o", Map.of(), false, List.of(SALES, LEGAL)));
        var seen = new AtomicReference<List<String>>();
        Runnable work = context.propagate((Runnable) () -> seen.set(context.current().teamSubjects()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(work).get(10, TimeUnit.SECONDS);
        }

        assertEquals(List.of(SALES, LEGAL), seen.get());
    }

    // ==================== the approver of a HITL resume ====================

    @Test
    @DisplayName("an approver's teams never reach the owner's identity, nor the call the approver runs")
    void approverTeamsStayOut() throws Exception {
        var owner = new CallerIdentity("owner-token", "alice", "o", Map.of(), false, List.of(SALES));
        var approver = new CallerIdentity("approver-token", "bob", "o", Map.of(), false, List.of(LEGAL, FINANCE));
        context.bind(owner);

        var ownerTeamsInside = new AtomicReference<List<String>>();
        var approverBinding = new AtomicReference<CallerIdentity>();
        var duringApprovedCall = new AtomicReference<CallerIdentity>();
        var ownerAfterApprovedCall = new AtomicReference<CallerIdentity>();

        context.withApprover(approver, () -> {
            ownerTeamsInside.set(context.current().teamSubjects());
            approverBinding.set(context.approver());
            duringApprovedCall.set(context.callAsApprover(context::current));
            ownerAfterApprovedCall.set(context.current());
            return null;
        }).call();

        assertEquals(List.of(SALES), ownerTeamsInside.get(), "the owner's identity keeps exactly the owner's teams");
        assertEquals(List.of(), approverBinding.get().teamSubjects(), "the approver is bound without their teams");
        assertEquals("bob", duringApprovedCall.get().userId(), "the approved call still runs as the approver");
        assertEquals("approver-token", duringApprovedCall.get().token());
        assertEquals(List.of(), duringApprovedCall.get().teamSubjects(), "but never with the approver's teams");
        assertEquals(owner, ownerAfterApprovedCall.get());
        assertEquals(owner, context.current());
        assertNull(context.approver());
    }

    @Test
    @DisplayName("a resume by an approver who is not the owner runs with no caller, so with no teams")
    void nonOwnerResumeHasNoTeams() throws Exception {
        var approver = new CallerIdentity("approver-token", "bob", "o", Map.of(), false, List.of(LEGAL));
        // ConversationHitlService: resumeCallerIdentity is null unless the approver
        // owns
        // the conversation; the approver is bound only as the approver.
        CallerIdentity resumeCaller = CallerIdentityContext.isSameUser(approver, "alice") ? approver : null;

        var seen = new AtomicReference<CallerIdentity>(new CallerIdentity("sentinel", "x", "o"));
        context.withIdentity(resumeCaller, context.withApprover(approver, () -> {
            seen.set(context.current());
            return null;
        })).call();

        assertNull(seen.get(), "the owner's turn gets no identity, so no team, from somebody else's approval");
    }

    // ==================== fixtures ====================

    /** An authenticated, non-OIDC identity whose groups come from an attribute. */
    private static SecurityIdentity mockIdentity(String principal, String token, List<String> groupPaths) {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(() -> principal);
        when(identity.getCredential(TokenCredential.class)).thenReturn(new TokenCredential(token, "bearer"));
        when(identity.getAttribute(GROUPS_CLAIM)).thenReturn(groupPaths);
        when(identity.getRoles()).thenReturn(Set.of());
        return identity;
    }

    /** The real SpaceContext, over the same identity, through its test seam. */
    private static SpaceContext spaceContextOver(SecurityIdentity identity) {
        var settings = mock(WorkspaceSettings.class);
        when(settings.getGroupsClaim()).thenReturn(GROUPS_CLAIM);
        return new SpaceContext(identity, settings);
    }

    private static ToolExecutionService toolExecutionServiceWith(CallerIdentityContext callerIdentityContext) throws Exception {
        var service = new ToolExecutionService();
        setField(service, "cacheService", mock(ToolCacheService.class));
        setField(service, "rateLimiter", mock(ToolRateLimiter.class));
        setField(service, "costTracker", mock(ToolCostTracker.class));
        setField(service, "meterRegistry", new SimpleMeterRegistry());
        setField(service, "callerIdentityContext", callerIdentityContext);
        service.init();
        return service;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
