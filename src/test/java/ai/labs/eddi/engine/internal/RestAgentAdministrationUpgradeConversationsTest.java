/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.internal.ConversationUpgradeService.UpgradeRefusedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.ConversationUpgradeReport;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestAgentAdministration.upgradeConversations")
class RestAgentAdministrationUpgradeConversationsTest {

    private static final Deployment.Environment ENV = Deployment.Environment.production;
    private static final String AGENT = "5aaf98e19f7dd421ac3c7de9";

    private ResourceAccessGuard guard;
    private ConversationUpgradeService service;
    private RestAgentAdministration admin;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        service = mock(ConversationUpgradeService.class);
        admin = new RestAgentAdministration(mock(IRuntime.class), mock(IAgentFactory.class), mock(IAgentStore.class), mock(IDeploymentStore.class),
                mock(IConversationMemoryStore.class), mock(IRestConversationStore.class), mock(IDocumentDescriptorStore.class),
                mock(IDeploymentListener.class), mock(IScheduleStore.class), mock(TenantQuotaService.class), guard);
        admin.conversationUpgradeService = service;
    }

    private static ConversationUpgradeReport report(boolean dryRun) {
        var totals = new ConversationUpgradeReport.VersionCounts(null, 3, 2, 0, Map.of("ended", 1L));
        return new ConversationUpgradeReport(AGENT, "production", dryRun, 7, 3, 7, false, List.of(), totals);
    }

    @Test
    @DisplayName("answers 200 with the report, passing the parameters through")
    void delegatesAndReturnsTheReport() {
        var report = report(false);
        when(service.upgrade(ENV, AGENT, List.of(5), false, 10)).thenReturn(report);

        Response response = admin.upgradeConversations(ENV, AGENT, false, List.of(5), 10);

        assertEquals(200, response.getStatus());
        assertSame(report, response.getEntity());
        verify(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
    }

    @Test
    @DisplayName("a missing dryRun is a dry run — the safe default holds even when the caller passes nothing")
    void nullDryRunIsADryRun() {
        when(service.upgrade(ENV, AGENT, null, true, null)).thenReturn(report(true));

        admin.upgradeConversations(ENV, AGENT, null, null, null);

        verify(service).upgrade(ENV, AGENT, null, true, null);
        verify(service, never()).upgrade(any(), any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("the caller needs EDIT on the agent — without it nothing is read or written")
    void requiresEditAccess() {
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> admin.upgradeConversations(ENV, AGENT, true, null, null));

        verify(service, never()).upgrade(any(), any(), any(), any(Boolean.class), any());
    }

    @Test
    @DisplayName("refusals map to 400, 404 and 409 with the message as plain text")
    void refusalsMapToStatuses() {
        var cases = Map.of(UpgradeRefusedException.Kind.INVALID, 400, UpgradeRefusedException.Kind.NOT_FOUND, 404,
                UpgradeRefusedException.Kind.CONFLICT, 409);
        cases.forEach((kind, status) -> {
            when(service.upgrade(eq(ENV), eq(AGENT), any(), any(Boolean.class), isNull()))
                    .thenThrow(new UpgradeRefusedException(kind, "why " + kind));

            Response response = admin.upgradeConversations(ENV, AGENT, true, null, null);

            assertEquals(status, response.getStatus());
            assertEquals("why " + kind, response.getEntity());
            assertEquals("text/plain", response.getMediaType().toString());
        });
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that does not echo the internals")
    void unexpectedFailureIsA500() {
        when(service.upgrade(eq(ENV), eq(AGENT), any(), any(Boolean.class), isNull()))
                .thenThrow(new IllegalStateException("mongodb://secret-host down"));

        var e = assertThrows(InternalServerErrorException.class, () -> admin.upgradeConversations(ENV, AGENT, true, null, null));

        assertEquals(false, e.getMessage().contains("secret-host"));
    }
}
