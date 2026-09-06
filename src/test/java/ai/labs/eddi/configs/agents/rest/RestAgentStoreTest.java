/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.ws.rs.BadRequestException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestAgentStoreTest {

    // Realistic IDs (extractResourceId requires 18+ hex chars)
    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String PKG1_ID = "ff00112233445566aa77";
    private static final String PKG2_ID = "bb99887766554433cc22";

    @Mock
    private IAgentStore AgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private CapabilityRegistryService capabilityRegistryService;
    @Mock
    private IDeploymentStore deploymentStore;
    @Mock
    private AgentSigningService agentSigningService;

    private RestAgentStore restAgentStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        restAgentStore = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), agentSigningService, "default");
        // The Agent is live at v1: a cascade is only allowed against the CURRENT
        // version, since it tears down workflows and schedules before the delete —
        // the only place the version used to be checked — has run.
        when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 1));
        // A workflow reference is version-pinned and is NOT re-pointed when the
        // workflow is edited, so the cascade resolves each one to the version that
        // EXISTS before it asks who else uses it or deletes anything.
        when(restWorkflowStore.getCurrentResourceId(PKG1_ID)).thenReturn(resourceId(PKG1_ID, 2));
        when(restWorkflowStore.getCurrentResourceId(PKG2_ID)).thenReturn(resourceId(PKG2_ID, 1));
        // The Agent stops being a referrer of its own workflows the moment it is
        // deleted; see referrers().
        doAnswer(invocation -> {
            agentDeleted.set(true);
            return null;
        }).when(AgentStore).delete(anyString(), anyInt());
        doAnswer(invocation -> {
            agentDeleted.set(true);
            return null;
        }).when(AgentStore).deleteAllPermanently(anyString());
    }

    static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    /** Helper to create a dummy DocumentDescriptor for reference-count mocking */
    private DocumentDescriptor dummyDescriptor() {
        return new DocumentDescriptor();
    }

    /**
     * Whether the Agent under test has been deleted yet; see {@link #referrers}.
     */
    private final AtomicBoolean agentDeleted = new AtomicBoolean();

    /**
     * The reverse lookup's answer, as the real store gives it: {@code total} counts
     * the Agent being deleted among a workflow's referrers, and that one disappears
     * once the Agent has been deleted — {@code AbstractResourceStore
     * .isStaleReference} drops a referrer that has no current row.
     *
     * <p>
     * Modelling that is what lets the post-delete re-check in {@code deleteAgent}
     * mean anything: against a stub frozen at its pre-delete answer, "only this
     * Agent used it" and "another Agent has picked it up since" look identical.
     * </p>
     */
    private List<DocumentDescriptor> referrers(int total) {
        int remaining = agentDeleted.get() ? total - 1 : total;
        List<DocumentDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < remaining; i++) {
            descriptors.add(dummyDescriptor());
        }
        return descriptors;
    }

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgentTests {

        @Test
        @DisplayName("should delete Agent without cascade when cascade=false")
        void deleteAgent_noCascade() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should delete the Agent's deployment records even without cascade")
        void deleteAgent_deletesDeploymentRecords() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            // A surviving record makes the runtime retry a doomed redeploy
            // of a now-missing Agent on every startup.
            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should keep deployment records when the Agent delete itself fails")
        void deleteAgent_keepsDeploymentRecordsWhenDeleteFails() throws Exception {
            // A stale/unknown version is rejected inside restVersionInfo.delete.
            // Clearing first would strip a still-live Agent of what it needs.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(deploymentStore, never()).deleteDeploymentInfos(any());
        }

        @Test
        @DisplayName("should still delete the Agent when clearing its deployment records fails")
        void deleteAgent_deploymentCleanupFailureIsNotFatal() throws Exception {
            when(deploymentStore.deleteDeploymentInfos(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceStoreException("boom", new RuntimeException()));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should cascade-delete packages when cascade=true and packages are not shared")
        void deleteAgent_cascade() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // Each package is only referenced by this one agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // permanent=false on the cascaded workflows even though the request said
            // permanent=true. These two assertions used to read `true` and so pinned the
            // defect: the reference guard above asks a VERSION-scoped question
            // ("who references W?version=2?") while a permanent delete is ID-scoped and
            // drops every version plus all history. The Agent itself is still erased
            // permanently — that is what the caller asked for and owns.
            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should delete deployment records on the cascade path too")
        void deleteAgent_cascade_alsoDeletesDeploymentRecords() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should skip cascade-delete of packages shared with other agents")
        void deleteAgent_cascade_skipsSharedWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // PKG1 is shared with 2 agents — should be SKIPPED
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(2));
            // PKG2 is only in this Agent — should be deleted
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // Only PKG2 should be deleted — PKG1 is shared
            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should continue deleting Agent even when package cascade fails")
        void deleteAgent_cascade_partialFailure() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // both packages only referenced by this agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(anyString(), anyInt(), eq(true))).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(PKG1_ID, 2, false, true)).thenThrow(new RuntimeException("Workflow in use"));
            when(restWorkflowStore.deleteWorkflow(PKG2_ID, 1, false, true)).thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should still delete Agent when Agent config not found for cascade")
        void deleteAgent_cascade_agentNotFound() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should handle empty packages list in cascade")
        void deleteAgent_cascade_emptyWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }
    }

    @Nested
    @DisplayName("deleteAgent — cascade version guard")
    class CascadeVersionGuard {

        /**
         * The cascade used to run against whatever version the request named, because
         * {@code agentStore.read} falls back to history for a superseded version and
         * the only version check lived in {@code restVersionInfo.delete()} — at the
         * very end. A stale tab deleting v1 of an Agent that is at v2 therefore
         * destroyed v1's workflows and schedules and only then answered 409.
         */
        @Test
        @DisplayName("refuses a stale version with 409 before touching workflows or schedules")
        void staleVersionRefusedBeforeAnyDeletion() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 2));

            var thrown = assertThrows(WebApplicationException.class,
                    () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, true));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), thrown.getResponse().getStatus());
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore, never()).delete(anyString(), anyInt());
            verify(AgentStore, never()).deleteAllPermanently(anyString());
        }

        /**
         * {@code ?version=0} is the documented "current version" shorthand. It used to
         * be resolved only inside {@code restVersionInfo.delete()}, so the cascade read
         * version 0, found nothing, and skipped itself with a WARN while the delete
         * went through — {@code cascade=true} silently did not cascade.
         */
        @Test
        @DisplayName("version=0 resolves to the current version and does cascade")
        void versionZeroCascades() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 0, false, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * The ordinary "purge what I already soft-deleted" flow:
         * {@code ?permanent=true&cascade=true} against an Agent with no current row.
         * {@code getCurrentResourceId} throws for it, and the documented contract is to
         * skip the cascade and still purge the history — so the guard has to answer
         * false rather than propagate, and must not dereference the missing current id,
         * which on this path would NPE partway through a destructive operation.
         */
        @Test
        @DisplayName("an already soft-deleted Agent skips the cascade and still purges")
        void softDeletedAgentSkipsCascadeButStillPurges() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * The capability index feeds {@code capabilityMatch} behaviour rules and A2A
         * discovery. Clearing it before the delete stripped a still-live Agent of what
         * it routes on when the delete then answered 409.
         */
        @Test
        @DisplayName("keeps the capability registration when the delete itself fails")
        void capabilityRegistrationSurvivesAFailedDelete() throws Exception {
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(capabilityRegistryService, never()).unregister(anyString());
        }

        @Test
        @DisplayName("clears the capability registration once the delete succeeded")
        void capabilityRegistrationClearedAfterDelete() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(capabilityRegistryService).unregister(AGENT_ID);
        }

        /**
         * Nothing else in the codebase removes an agent's private key, and the vault
         * entry outlives the config that documented what it was for. This assertion
         * used to pass {@code permanent=false} — see
         * {@link #keepsSigningKeyPairOnASoftDelete()} for why that was wrong.
         */
        @Test
        @DisplayName("removes the signing key from the vault when the Agent is permanently deleted")
        void deletesSigningKeyPair() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            // An EMPTY version list, not null: this Agent carries the legacy
            // unversioned key and no rotated ones, and null is what asks for the
            // blind 1..100 sweep.
            verify(agentSigningService).deleteKeyPair("default", AGENT_ID, List.of());
        }

        /**
         * The two-step purge the API documents as ordinary: soft-delete an Agent, then
         * come back and {@code DELETE ?permanent=true}. The probe used to read through
         * {@code agentStore.read}, which throws {@code ResourceNotFoundException} for a
         * history row flagged deleted — i.e. for EVERY soft-deleted Agent — so it
         * answered "no key material" and the Ed25519 private key stayed in the vault
         * after the config and all of its history had been erased. That is the leak
         * this cleanup exists to close, surviving on the only recommended path to
         * closing it.
         */
        @Test
        @DisplayName("purging an already soft-deleted Agent still removes its vault keys")
        void purgingASoftDeletedAgentStillRemovesItsKeys() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("soft-deleted"));
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithRotatedKeys());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService).deleteKeyPair("default", AGENT_ID, List.of(1, 3));
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * A soft delete is the deliberately recoverable path, and the Ed25519 private
         * key is the one thing about an Agent that cannot be recreated: no endpoint
         * generates one (see {@code validateSecurityFlags}), so an Agent restored from
         * a ZIP backup would come back with a public key whose private half is gone and
         * {@code signInterAgentMessages} permanently broken. The vault cleanup was
         * briefly unconditional, which made every recoverable delete irreversible for
         * key material.
         */
        @Test
        @DisplayName("keeps the signing key in the vault on a soft delete")
        void keepsSigningKeyPairOnASoftDelete() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("does not probe the vault for an Agent with no key material")
        void skipsVaultForAgentWithoutIdentity() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(new AgentConfiguration());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
        }

        /**
         * The key-cleanup probe is best-effort by design: an Agent that cannot be read
         * reports "no key material" rather than failing the delete. A leaked vault
         * entry is recoverable; a config that cannot be deleted is not — so the probe
         * must never decide whether the delete happens.
         */
        @Test
        @DisplayName("an unreadable Agent is still deleted, with the vault probe answering no")
        void unreadableAgentDoesNotBlockThePermanentDelete() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, false));

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * The pre-cascade version check is a check-then-act, exactly as it was in
         * {@code RestWorkflowStore.deleteWorkflow}. A concurrent update committing
         * between it and {@code restVersionInfo.delete()} — a PUT, or the 10-second
         * deployment sweep touching this Agent — used to leave the schedules deleted
         * and every exclusively owned workflow (and, through deleteWorkflow's own
         * cascade, its extensions) torn down while the delete answered 409 "nothing was
         * deleted". The live Agent kept its config and lost everything it pointed at,
         * and the 409 contract {@code IRestAgentStore} documents was false on this
         * path. Deleting the Agent FIRST makes the store's own version check the gate.
         */
        @Test
        @DisplayName("an Agent that moved on between the guard and the delete loses neither workflows nor schedules")
        void concurrentUpdateBetweenGuardAndDeleteCascadesNothing() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            // The guard saw v1; by the time the delete runs the Agent is at v2.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
        }

        /**
         * The other half of the reordering: DECIDING still happens before the delete,
         * so the reference guard counts this Agent among a workflow's referrers (hence
         * the {@code > 1} test). Were the reverse lookup moved after the delete, the
         * Agent would no longer count itself, a workflow shared with exactly one other
         * Agent would come back as size 1, and the cascade would delete a workflow that
         * is still in use.
         */
        @Test
        @DisplayName("a workflow shared with another Agent survives the cascade")
        void sharedWorkflowIsNotCascadeDeleted() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // This Agent plus one other — the guard must read that as "still referenced".
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true))
                    .thenAnswer(invocation -> referrers(2));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        /**
         * The window the ordering leaves open: the workflow was judged exclusive BEFORE
         * this Agent was deleted, and another Agent can be created — or re-pointed at
         * it — between that decision and the cascade delete. Nothing downstream
         * re-asks: {@code deleteWorkflow} only checks WORKFLOW referrers, not Agent
         * ones. So the newly shared workflow (and, through that delete's own cascade,
         * its rule sets, output sets and dictionaries) was torn down under a live
         * Agent. The re-check runs after this Agent is gone, so anything the reverse
         * lookup still returns is a referrer that must stop the delete.
         */
        @Test
        @DisplayName("a workflow another Agent picks up during the cascade is not deleted")
        void workflowThatBecomesSharedDuringTheCascadeIsSkipped() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // One referrer at both moments, but not the SAME one: when the cascade is
            // planned it is this Agent (so the workflow is a candidate), and by the time
            // the cascade delete runs this Agent is gone and the one referrer is an
            // Agent that has just been pointed at the workflow.
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenReturn(List.of(dummyDescriptor()));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        /**
         * The counterpart to
         * {@link #workflowThatBecomesSharedDuringTheCascadeIsSkipped} — without it,
         * that test would pass equally against a cascade that had simply stopped
         * deleting anything.
         */
        @Test
        @DisplayName("a workflow nobody picks up during the cascade is still deleted")
        void workflowThatStaysExclusiveIsStillCascadeDeleted() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
        }

        /**
         * A reference to a workflow that has no live version left is nothing to cascade
         * at — there is no version to address the delete at, and guessing the pinned
         * one would resurrect the very stale-version delete the guard exists to refuse.
         * It is skipped, and the workflows after it in the list are still planned: one
         * dangling reference must not disarm the whole cascade.
         */
        @Test
        @DisplayName("a workflow with no live version left is skipped, and the rest of the cascade still runs")
        void workflowWithNoLiveVersionIsSkipped() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(restWorkflowStore.getCurrentResourceId(PKG1_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("already soft-deleted"));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * FAIL CLOSED per workflow. A reference check that cannot answer is not a
         * licence to delete, and letting the throwable out of the planning step would
         * abort the request before the Agent itself had been deleted — leaving the
         * Agent undeletable for as long as the store misbehaves.
         */
        @Test
        @DisplayName("a reference check that blows up skips that workflow and still deletes the Agent")
        void referenceCheckFailureSkipsOnlyThatWorkflow() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true))
                    .thenThrow(new IResourceStore.ResourceStoreException("reverse lookup broken"));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * An {@code identity} block is not by itself key material: an Agent can carry
         * one for its name or metadata with neither a legacy {@code publicKey} nor any
         * rotated {@code keys}. Destroying vault entries is irreversible — there is no
         * key-generation endpoint — so the cleanup must only run for an Agent that
         * actually declares a key.
         */
        @Test
        @DisplayName("an identity block with no key material does not trigger the vault cleanup")
        void identityWithoutKeysDoesNotTriggerVaultCleanup() throws Exception {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("   ");
            identity.setKeys(new ArrayList<>());
            config.setIdentity(identity);
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(config);

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        private AgentConfiguration agentWithSigningIdentity() {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("MCowBQYDK2VwAyEA-not-a-real-key");
            config.setIdentity(identity);
            return config;
        }

        /**
         * A rotated identity whose declared versions skip a number, as rotateKey
         * allows.
         */
        private AgentConfiguration agentWithRotatedKeys() {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "k1"), AgentPublicKey.createCurrent(3, "k3"))));
            config.setIdentity(identity);
            return config;
        }
    }

    // --- Wave 3: Security flag validation ---

    @Nested
    @DisplayName("rejectInertSecurityFlags")
    class SecurityFlagValidationTests {

        @Test
        @DisplayName("createAgent should reject signInterAgentMessages=true with HTTP 400")
        void createAgent_rejectsSignInterAgentMessages() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should reject requirePeerVerification=true with HTTP 400")
        void createAgent_rejectsRequirePeerVerification() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setRequirePeerVerification(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should allow null security block")
        void createAgent_allowsNullSecurity() throws Exception {
            var config = new AgentConfiguration();
            config.setSecurity(null);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should allow security block with all flags false")
        void createAgent_allowsAllFlagsFalse() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            // all default to false
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("updateAgent should reject signInterAgentMessages=true")
        void updateAgent_rejectsSecurityFlags() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.updateAgent(AGENT_ID, 1, config));
        }

        @Test
        @DisplayName("duplicateAgent should reject security flags from source config when no keys")
        void duplicateAgent_rejectsSecurityFlags() throws Exception {
            var sourceConfig = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            // No identity/keys — should fail validation
            sourceConfig.setSecurity(security);
            sourceConfig.setWorkflows(new ArrayList<>());

            when(AgentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            assertThrows(BadRequestException.class,
                    () -> restAgentStore.duplicateAgent(AGENT_ID, 1, false));
        }

        @Test
        @DisplayName("createAgent should accept crypto flags when rotated keys exist")
        void createAgent_acceptsCryptoWithRotatedKeys() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            var identity = new AgentConfiguration.AgentIdentity();
            // No legacy publicKey, but rotated keys list is populated
            identity.setKeys(List.of(
                    AgentPublicKey.createCurrent(1, "base64key==")));
            config.setIdentity(identity);

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should accept crypto flags when legacy publicKey exists")
        void createAgent_acceptsCryptoWithLegacyKey() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("legacyBase64Key==");
            config.setIdentity(identity);

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }
    }
}
