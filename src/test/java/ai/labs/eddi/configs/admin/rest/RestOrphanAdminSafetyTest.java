/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Safety behaviour of the orphan admin endpoint.
 *
 * <p>
 * Two properties are pinned here because getting either wrong permanently
 * destroys configuration:
 * </p>
 * <ol>
 * <li>the descriptor page walk must actually visit every page — a truncated
 * walk leaves references out of the "referenced" set, which promotes live
 * resources to "orphan"</li>
 * <li>the purge must refuse to run on an incomplete reference scan</li>
 * </ol>
 *
 * <p>
 * Resource ids are 24-char hex because {@code RestUtilities.extractResourceId}
 * returns a null id for anything shorter or non-hex, which would make these
 * fixtures pass vacuously.
 * </p>
 */
@DisplayName("RestOrphanAdmin — scan completeness and purge safety")
class RestOrphanAdminSafetyTest {

    private static final int BATCH_SIZE = 200;
    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final URI AGENT_URI = URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1");

    @Mock
    private IAgentStore agentStore;
    @Mock
    private IWorkflowStore workflowStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IDeploymentStore deploymentStore;

    private RestOrphanAdmin restOrphanAdmin;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restOrphanAdmin = new RestOrphanAdmin(agentStore, workflowStore, documentDescriptorStore, resourceClientLibrary, restWorkflowStore,
                deploymentStore);
    }

    private static DocumentDescriptor descriptor(URI resource, String name) {
        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName(name);
        return descriptor;
    }

    private static IResourceStore.IResourceId resourceId(String id, Integer version) {
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

    private static WorkflowConfiguration emptyWorkflow() {
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of());
        return config;
    }

    private static List<DocumentDescriptor> fullPage(String type) {
        List<DocumentDescriptor> page = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            page.add(descriptor(URI.create("eddi://" + type + "/store/items/" + String.format("%024x", i) + "?version=1"), "d" + i));
        }
        return page;
    }

    @Nested
    @DisplayName("descriptor paging")
    class Paging {

        @Test
        @DisplayName("advances by PAGE index, so a second page is actually requested")
        void walksEveryPage() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), eq(BATCH_SIZE), anyBoolean()))
                    .thenReturn(fullPage("ai.labs.rules"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), eq(BATCH_SIZE), anyBoolean()))
                    .thenReturn(List.of(descriptor(URI.create("eddi://ai.labs.rules/rulestore/rulesets/ffffffffffffffffffffffff?version=1"),
                            "page-two-item")));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            // Page 1 must have been requested with index == 1 (not 200, which is what
            // advancing by batch size produced and which always returned empty).
            verify(documentDescriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), eq(BATCH_SIZE), anyBoolean());
            assertEquals(BATCH_SIZE + 1, report.getTotalOrphans(), "both pages should contribute orphans");
            assertTrue(report.getOrphans().stream().anyMatch(o -> "page-two-item".equals(o.getName())),
                    "the second page's descriptor must appear in the report");
        }

        @Test
        @DisplayName("a type that never stops paging aborts the purge instead of truncating")
        void ceilingAbortsPurgeRatherThanTruncating() throws Exception {
            // Every page full, forever: the walk must hit MAX_PAGES and raise, not
            // quietly return a partial set. A truncated scan of the REFERENCE side is
            // what makes live resources look unreferenced, so it must never reach the
            // delete loop.
            AgentConfiguration readableAgent = new AgentConfiguration();
            readableAgent.setWorkflows(List.of());

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(fullPage("ai.labs.agent"));
            // Every descriptor must read cleanly, so the ONLY thing that can mark the
            // scan incomplete is the page ceiling. Without this the mock returns null,
            // the traversal NPEs, and the test passes for the wrong reason even with
            // the ceiling removed.
            when(agentStore.read(anyString(), any())).thenReturn(readableAgent);

            WebApplicationException thrown = assertThrows(WebApplicationException.class, () -> restOrphanAdmin.purgeOrphans(false));

            assertEquals(409, thrown.getResponse().getStatus());
            assertTrue(thrown.getResponse().getEntity().toString().contains("exceeded"),
                    "the refusal must name the page ceiling, got: " + thrown.getResponse().getEntity());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("stops on the first partial page")
        void stopsOnPartialPage() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restOrphanAdmin.scanOrphans(false);

            verify(documentDescriptorStore, never()).readDescriptors(anyString(), anyString(), eq(1), anyInt(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("purge refuses an incomplete reference scan")
    class PurgeSafety {

        @Test
        @DisplayName("an unreadable Agent aborts the purge with 409 and deletes nothing")
        void unreadableAgentAbortsPurge() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "broken-agent")));
            when(agentStore.read(eq(AGENT_ID), any())).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            WebApplicationException thrown = assertThrows(WebApplicationException.class, () -> restOrphanAdmin.purgeOrphans(true));

            assertEquals(409, thrown.getResponse().getStatus());
            // The reason must reach the caller, not just the server log — the
            // (String, Status) constructor sets no entity, so this pins that the
            // Response is built explicitly.
            assertTrue(thrown.getResponse().hasEntity(), "409 must carry a body explaining the refusal");
            assertTrue(thrown.getResponse().getEntity().toString().contains("mongo down"),
                    "the body must name the underlying cause, got: " + thrown.getResponse().getEntity());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("a missing Agent resource is a real orphan, not a scan failure — purge proceeds")
        void missingAgentDoesNotAbortPurge() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "deleted-agent")));
            when(agentStore.read(eq(AGENT_ID), any())).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(0, report.getTotalOrphans());
        }

        @Test
        @DisplayName("a complete scan purges normally")
        void completeScanPurges() throws Exception {
            URI orphan = URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445568?version=1");
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(orphan, "unused-ruleset")));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(1, report.getDeletedCount());
            verify(resourceClientLibrary).deleteResource(orphan, true);
        }

        @Test
        @DisplayName("scanOrphans still returns a report when the reference scan is incomplete")
        void scanToleratesIncompleteReferenceSet() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "broken-agent")));
            when(agentStore.read(eq(AGENT_ID), any())).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertEquals(0, report.getDeletedCount(), "a scan must never delete");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("includeDeleted is an inclusion flag, not an equality filter")
    class IncludeDeletedSemantics {

        @Test
        @DisplayName("false constrains to live resources only")
        void falseFiltersToLive() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restOrphanAdmin.scanOrphans(false);

            verify(documentDescriptorStore, atLeastOnce()).readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), eq(false));
        }

        @Test
        @DisplayName("true is forwarded so the store drops the deleted constraint entirely")
        void trueForwardsInclusion() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restOrphanAdmin.scanOrphans(true);

            // The store-level change is what makes true mean "live AND soft-deleted";
            // previously it meant "soft-deleted only", so a scan(false)/purge(true) pair
            // acted on disjoint sets.
            verify(documentDescriptorStore, atLeastOnce()).readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), eq(true));
        }
    }

    @Nested
    @DisplayName("referenced resources are protected")
    class ReferenceProtection {

        @Test
        @DisplayName("a workflow referenced by an Agent is not reported as an orphan")
        void referencedWorkflowIsNotOrphan() throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445569?version=1");

            AgentConfiguration agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(workflowUri));

            WorkflowConfiguration workflowConfig = new WorkflowConfiguration();
            workflowConfig.setWorkflowSteps(List.of());

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "live-agent")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "live-workflow")));
            when(agentStore.read(eq(AGENT_ID), any())).thenReturn(agentConfig);
            when(workflowStore.read(eq("aabbccddeeff112233445569"), any())).thenReturn(workflowConfig);

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(0, report.getTotalOrphans(), "a referenced workflow must never be purged");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        /**
         * The version-skew case, which is the NORMAL state rather than an edge case:
         * {@code DocumentDescriptorFilter} rewrites a descriptor's {@code resource} to
         * the new version on every PUT, while references stay pinned where they were.
         * So one edit of a rule set leaves the descriptor saying {@code ?version=2} and
         * every un-re-pointed workflow still saying {@code ?version=1}. A literal
         * string compare called that rule set an orphan, and the purge — which deletes
         * every version and all history — destroyed a config a live workflow was still
         * resolving.
         */
        @Test
        @DisplayName("a resource referenced at an older pinned version is not an orphan")
        void referenceAtOlderVersionProtectsCurrentVersion() throws Exception {
            String ruleSetId = "aabbccddeeff11223344556a";
            URI pinnedByWorkflow = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");
            URI currentDescriptorResource = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=2");
            String workflowId = "aabbccddeeff112233445569";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            AgentConfiguration agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(workflowUri));

            WorkflowConfiguration workflowConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep rulesStep = new WorkflowConfiguration.WorkflowStep();
            rulesStep.setType(URI.create("eddi://ai.labs.behavior"));
            rulesStep.setConfig(new HashMap<>(Map.of("uri", pinnedByWorkflow.toString())));
            workflowConfig.setWorkflowSteps(List.of(rulesStep));

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "live-agent")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "live-workflow")));
            // The rule set's descriptor points at v2 — the version the last PUT created.
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(currentDescriptorResource, "edited-ruleset")));
            when(agentStore.read(eq(AGENT_ID), any())).thenReturn(agentConfig);
            when(workflowStore.read(eq(workflowId), any())).thenReturn(workflowConfig);

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(0, report.getTotalOrphans(), "any referenced version must protect the resource");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("the purge converges and respects what is live right now")
    class PurgeConvergence {

        /**
         * {@code RestVersionInfo.markDescriptorDeleted} only FLAGS descriptors — it has
         * to, because on the HTTP path the descriptor filter reads one back after the
         * delete and a missing row answers 404 to a delete that succeeded. But a
         * flagged descriptor whose resource is gone is exactly what
         * {@code readDescriptors(includeDeleted=true)} selects, so every purged
         * resource came back as an orphan on the next {@code includeDeleted=true}
         * sweep, {@code deleteAllPermanently} on a non-existent id answered silently,
         * and {@code deletedCount} counted it again — forever. That filter does not run
         * for {@code /administration/orphans}, so erasing the row here is both safe and
         * what makes the sweep converge.
         */
        @Test
        @DisplayName("a purged resource's descriptor is removed, so the next sweep does not re-report it")
        void purgeRemovesTheDescriptorSoTheSweepConverges() throws Exception {
            String ruleSetId = "aabbccddeeff11223344557a";
            URI orphan = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(orphan, "already-soft-deleted-ruleset")));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(1, report.getDeletedCount());
            verify(resourceClientLibrary).deleteResource(orphan, true);
            verify(documentDescriptorStore).deleteAllDescriptor(ruleSetId);
        }

        @Test
        @DisplayName("an orphaned workflow's descriptor is removed too")
        void purgingAWorkflowAlsoRemovesItsDescriptor() throws Exception {
            String workflowId = "aabbccddeeff11223344557b";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "unreferenced-workflow")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restOrphanAdmin.purgeOrphans(true);

            verify(documentDescriptorStore).deleteAllDescriptor(workflowId);
        }

        /**
         * A permanent delete is now refused unless it is addressed at the resource's
         * CURRENT version ({@code RestVersionInfo.requireCurrentVersion}), so the purge
         * must resolve the version rather than trust the descriptor's. Descriptor
         * versions go stale routinely: {@code DocumentDescriptorFilter} advances them
         * only on an HTTP PUT/PATCH, so every in-process update path — MCP's injected
         * facades, ZIP import, the upgrade executor — leaves the descriptor at v1 while
         * the resource is at v2, the skew {@code resourceKey}'s Javadoc calls normal.
         * Addressed at the descriptor's version, every such orphan failed with a caught
         * 409 on every run and was never purged: the non-convergence this endpoint
         * exists to remove.
         */
        @Test
        @DisplayName("an orphan whose descriptor lags the resource is deleted at the LIVE version, not the descriptor's")
        void purgeAddressesTheLiveVersionNotTheDescriptorsStaleOne() throws Exception {
            String ruleSetId = "aabbccddeeff11223344558a";
            URI staleDescriptorUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");
            URI liveUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=2");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(staleDescriptorUri, "edited-through-mcp")));
            // Edited in-process, so the resource moved to v2 and the descriptor did not.
            when(resourceClientLibrary.getCurrentResourceId(staleDescriptorUri)).thenReturn(resourceId(ruleSetId, 2));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            verify(resourceClientLibrary).deleteResource(liveUri, true);
            verify(resourceClientLibrary, never()).deleteResource(eq(staleDescriptorUri), anyBoolean());
            assertEquals(1, report.getDeletedCount(), "the orphan must actually be purged, not 409 on every run for ever");
        }

        /**
         * The workflow arm of the same defect: {@code deleteWorkflow} routes to
         * {@code RestVersionInfo.delete} exactly as the extension facades do.
         */
        @Test
        @DisplayName("an orphaned workflow whose descriptor lags is deleted at the live version too")
        void purgeAddressesTheLiveWorkflowVersion() throws Exception {
            String workflowId = "aabbccddeeff11223344558b";
            URI staleDescriptorUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(staleDescriptorUri, "edited-through-import")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(workflowStore.getCurrentResourceId(workflowId)).thenReturn(resourceId(workflowId, 4));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restOrphanAdmin.purgeOrphans(true);

            verify(restWorkflowStore).deleteWorkflow(workflowId, 4, true, false);
            verify(restWorkflowStore, never()).deleteWorkflow(eq(workflowId), eq(1), anyBoolean(), anyBoolean());
        }

        /**
         * The re-check and the delete must ask about the SAME version. Both reverse
         * lookups take {@code includePreviousVersions=true}, which walks from the
         * version given DOWN to 1, so a referrer pinning a version ABOVE the one asked
         * about is invisible. Asked at a stale descriptor's v1, a workflow that started
         * referencing this rule set at v2 in the mark/sweep window would be missed —
         * and now that the delete is addressed at the live version it would actually
         * succeed, permanently erasing a resource in use. (Before the version was
         * resolved at all, that delete answered 409 and the gap was closed only by
         * accident.)
         */
        @Test
        @DisplayName("the pre-delete re-check asks at the live version, so a referrer at a newer version still protects the resource")
        void recheckAsksAtTheLiveVersionNotTheDescriptorsStaleOne() throws Exception {
            String ruleSetId = "aabbccddeeff11223344558d";
            URI staleDescriptorUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");
            URI liveUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=2");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(staleDescriptorUri, "edited-through-mcp")));
            when(resourceClientLibrary.getCurrentResourceId(staleDescriptorUri)).thenReturn(resourceId(ruleSetId, 2));
            // A workflow started referencing it at v2 after the scan. The walk from v1
            // downwards cannot see that; the walk from v2 can.
            when(workflowStore.getWorkflowDescriptorsContainingResource(liveUri.toString(), true))
                    .thenReturn(List.of(descriptor(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/"
                            + "aabbccddeeff11223344558e?version=1"), "a-workflow-that-uses-it")));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            assertEquals(0, report.getDeletedCount(), "a resource referenced at its live version must never be purged");
        }

        /**
         * The soft-deleted case, which {@code requireCurrentVersion} deliberately
         * admits: there is no live version for the request to be stale against, so the
         * two-step "soft delete, then purge the history" flow must keep working. The
         * descriptor's version is the only address available, and it has to be used.
         */
        @Test
        @DisplayName("a resource with no live version left is still purged at the descriptor's version")
        void purgeFallsBackToTheDescriptorVersionWhenNothingIsLive() throws Exception {
            String ruleSetId = "aabbccddeeff11223344558c";
            URI orphan = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(orphan, "already-soft-deleted-ruleset")));
            when(resourceClientLibrary.getCurrentResourceId(orphan)).thenReturn(null);

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            verify(resourceClientLibrary).deleteResource(orphan, true);
            assertEquals(1, report.getDeletedCount());
        }

        /**
         * Mark and sweep are separated by however long the scan takes. A workflow that
         * starts referencing a candidate in that window makes it live again, and the
         * purge would erase every version of something in use. The re-check is a fresh
         * reverse lookup taken immediately before the delete; it narrows the window
         * rather than closing it, and it fails closed.
         */
        @Test
        @DisplayName("a candidate that became referenced after the scan is not purged")
        void aCandidateThatBecameReferencedIsSkipped() throws Exception {
            URI orphan = URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff11223344557c?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(orphan, "just-became-referenced")));
            // The scan saw nothing referencing it; by the time the purge runs, a
            // workflow does.
            when(workflowStore.getWorkflowDescriptorsContainingResource(eq(orphan.toString()), eq(true)))
                    .thenReturn(List.of(descriptor(orphan, "referring-workflow")));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("a re-check that cannot answer fails closed")
        void aFailedRecheckFailsClosed() throws Exception {
            URI orphan = URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff11223344557d?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(orphan, "unanswerable")));
            when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean()))
                    .thenThrow(new IResourceStore.ResourceStoreException("index unavailable"));

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(0, report.getDeletedCount(), "a permanent delete must never be decided on an unanswered query");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("what counts as a reference")
    class ReferenceIdentity {

        /**
         * Deployments are version-pinned: {@code checkDeployments} reads
         * {@code (agentId, agentVersion)} out of the deployment store and redeploys
         * exactly that version on every startup. Only each agent's CURRENT version used
         * to contribute references, so editing a deployed agent to point at a new
         * workflow made the old one look unreferenced — and purging it left the
         * still-deployed version unable to resolve its own workflow, history rows gone
         * and no recovery path.
         */
        @Test
        @DisplayName("a workflow referenced only by a DEPLOYED older agent version is not an orphan")
        void deployedOlderAgentVersionProtectsItsWorkflow() throws Exception {
            String workflowId = "aabbccddeeff11223344558a";
            URI oldWorkflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            // The agent's CURRENT version (v2) has dropped that workflow entirely.
            AgentConfiguration currentAgent = new AgentConfiguration();
            currentAgent.setWorkflows(List.of());
            // The DEPLOYED version (v1) still points at it.
            AgentConfiguration deployedAgent = new AgentConfiguration();
            deployedAgent.setWorkflows(List.of(oldWorkflowUri));

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=2"),
                            "edited-agent")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(oldWorkflowUri, "still-deployed-workflow")));
            when(agentStore.read(AGENT_ID, 2)).thenReturn(currentAgent);
            when(agentStore.read(AGENT_ID, 1)).thenReturn(deployedAgent);
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployment(AGENT_ID, 1)));

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(0, report.getTotalOrphans(), "a deployed agent version's workflow must never be purged");
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("a deployment store that cannot be read makes the scan incomplete and refuses the purge")
        void unreadableDeploymentStoreRefusesThePurge() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            WebApplicationException thrown = assertThrows(WebApplicationException.class, () -> restOrphanAdmin.purgeOrphans(false));

            assertEquals(409, thrown.getResponse().getStatus());
            assertFalse(restOrphanAdmin.scanOrphans(false).isScanComplete(), "the read-only scan must say so as well");
        }

        /**
         * {@code ResourceClientLibrary.init()} registers three stores under two
         * authorities each, so a workflow step written through REST or MCP with the
         * legacy authority resolves perfectly at runtime — while the descriptor always
         * carries the canonical one {@code RestVersionInfo.create} writes. Only ZIP
         * import normalises. A literal compare therefore reported a rule set a live
         * workflow still references as unreferenced, and the purge erased it.
         */
        @Test
        @DisplayName("a reference through a legacy authority protects the canonical resource")
        void legacyAuthorityReferenceProtectsTheCanonicalResource() throws Exception {
            String ruleSetId = "aabbccddeeff11223344558b";
            URI legacyReference = URI.create("eddi://ai.labs.behavior/behaviorstore/behaviorsets/" + ruleSetId + "?version=1");
            URI canonicalDescriptorResource = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=2");
            String workflowId = "aabbccddeeff11223344558c";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            AgentConfiguration agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(workflowUri));

            WorkflowConfiguration workflowConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep rulesStep = new WorkflowConfiguration.WorkflowStep();
            rulesStep.setType(URI.create("eddi://ai.labs.behavior"));
            rulesStep.setConfig(new HashMap<>(Map.of("uri", legacyReference.toString())));
            workflowConfig.setWorkflowSteps(List.of(rulesStep));

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "live-agent")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "live-workflow")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(canonicalDescriptorResource, "ruleset-referenced-by-legacy-uri")));
            when(agentStore.read(eq(AGENT_ID), any())).thenReturn(agentConfig);
            when(workflowStore.read(eq(workflowId), any())).thenReturn(workflowConfig);

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(0, report.getTotalOrphans(), "a legacy-authority reference names the same resource the runtime resolves");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    private static DeploymentInfo deployment(String agentId, int agentVersion) {
        DeploymentInfo info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(agentVersion);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        return info;
    }

    @Nested
    @DisplayName("the report tells the operator what actually happened")
    class ReportHonesty {

        /**
         * {@code ResourceClientLibrary} registers no {@code ai.labs.workflow} proxy,
         * and {@code deleteResource} used to answer {@code Response.ok()} for an
         * unknown type. So every orphaned workflow — the largest category, since a
         * deleted agent is exactly what leaves workflows unreferenced — was counted as
         * purged and logged as purged while nothing was deleted, forever.
         */
        @Test
        @DisplayName("an orphaned workflow is deleted through the workflow store, not the (unregistered) proxy")
        void orphanedWorkflowIsActuallyDeleted() throws Exception {
            String workflowId = "aabbccddeeff11223344556b";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "unreferenced-workflow")));
            // The workflow itself resolves — it simply is not referenced by any Agent.
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(1, report.getDeletedCount());
            // permanent=true (that is what a purge means); cascade=false, because every
            // resource the workflow references is itself enumerated by this same scan.
            verify(restWorkflowStore).deleteWorkflow(workflowId, 1, true, false);
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("a workflow delete that fails is not counted as purged")
        void failedWorkflowDeleteIsNotCounted() throws Exception {
            String workflowId = "aabbccddeeff11223344556c";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "stuck-workflow")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new IllegalStateException("still deployed"));

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount(), "deletedCount must count deletions, not attempts");
        }

        /**
         * The GET is the operator's review surface for an irreversible operation, and a
         * transient store failure drops that document's references from the set —
         * promoting everything only it referenced to "orphan". The purge refuses in
         * that case; the read-only scan answers, so it has to say the list is partial.
         */
        @Test
        @DisplayName("an incomplete reference scan is reported as such, with a reason")
        void incompleteScanIsFlagged() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "broken-agent")));
            when(agentStore.read(eq(AGENT_ID), any())).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertFalse(report.isScanComplete(), "a partial scan must not look like a clean one");
            assertTrue(report.getScanWarning() != null && report.getScanWarning().contains("mongo down"),
                    "the warning must name the cause, got: " + report.getScanWarning());
        }

        @Test
        @DisplayName("a clean scan reports scanComplete=true and no warning")
        void completeScanIsFlagged() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete());
            assertNull(report.getScanWarning());
        }

        /**
         * {@code deletedCount} is the operator's only feedback for an irreversible
         * operation, so every way a workflow delete can fail to happen must leave it
         * uncounted. These are the three that do not raise on their own: a URI with no
         * usable resource id, a null Response, and a non-2xx answer.
         */
        @Test
        @DisplayName("a workflow URI with no usable resource id is not counted as purged")
        void workflowUriWithoutResourceIdIsNotCounted() throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/not-a-hex-id?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "unidentifiable-workflow")));

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount(), "nothing was deleted, so nothing may be counted");
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("a null delete response is not counted as purged")
        void nullDeleteResponseIsNotCounted() throws Exception {
            String workflowId = "aabbccddeeff11223344556d";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "silent-workflow")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(null);

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount(), "a delete that answered nothing is not a deletion");
        }

        @Test
        @DisplayName("a non-2xx delete response is not counted as purged")
        void nonSuccessDeleteResponseIsNotCounted() throws Exception {
            String workflowId = "aabbccddeeff11223344556e";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "conflicted-workflow")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(emptyWorkflow());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(Response.status(409).build());

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount(), "a 409 is a refusal, not a deletion");
        }
    }

    /**
     * A workflow step whose {@code uri} is not a parsable URI. Recording it
     * verbatim protects nothing, which is the documented trade — but the reference
     * traversal must survive it, because an exception here is caught per workflow
     * and costs the scan every OTHER reference that workflow holds, which is what
     * turns live resources into "orphans".
     */
    @Nested
    @DisplayName("a malformed step URI does not derail the reference scan")
    class MalformedStepUri {

        @Test
        @DisplayName("the rest of the workflow's references are still collected, and the scan stays complete")
        void malformedUriIsRecordedAndTheScanContinues() throws Exception {
            String workflowId = "aabbccddeeff11223344556f";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");
            String ruleSetId = "aabbccddeeff112233445570";
            URI ruleSetUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");

            var malformedStep = new WorkflowConfiguration.WorkflowStep();
            malformedStep.setType(URI.create("eddi://ai.labs.output"));
            malformedStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/output sets/x?version=1")));

            var goodStep = new WorkflowConfiguration.WorkflowStep();
            goodStep.setType(URI.create("eddi://ai.labs.behavior"));
            goodStep.setConfig(new HashMap<>(Map.of("uri", ruleSetUri.toString())));

            var workflowConfig = new WorkflowConfiguration();
            workflowConfig.setWorkflowSteps(List.of(malformedStep, goodStep));

            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "workflow-with-a-typo")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(ruleSetUri, "still-referenced-ruleset")));
            when(workflowStore.read(eq(workflowId), any())).thenReturn(workflowConfig);

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete(), "a malformed reference is not a scan failure, got: " + report.getScanWarning());
            assertTrue(report.getOrphans().stream().noneMatch(o -> ruleSetUri.equals(o.getResourceUri())),
                    "the reference AFTER the malformed one must still protect its resource");
        }
    }

    /**
     * The deployment sweep and the purge's own bookkeeping.
     *
     * <p>
     * Two properties are pinned here, and they pull in opposite directions. A
     * deployment row that protects nothing — no agent id, no workflows, an agent
     * version already gone from the store — must NOT make the scan incomplete, or
     * the purge is permanently refused on any installation with a stale deployment
     * row. A deployment or workflow that cannot be READ must, because everything
     * that version protects would otherwise be reported as an orphan and erased.
     * </p>
     */
    @Nested
    @DisplayName("deployment sweep and purge bookkeeping")
    class DeploymentSweepAndBookkeeping {

        private static final String DEPLOYED_AGENT_ID = "aabbccddeeff112233445580";
        private static final String DEPLOYED_WORKFLOW_ID = "aabbccddeeff112233445581";
        private static final URI DEPLOYED_WORKFLOW_URI = URI
                .create("eddi://ai.labs.workflow/workflowstore/workflows/" + DEPLOYED_WORKFLOW_ID + "?version=3");

        private void noDescriptors() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("deployment rows that protect nothing leave the scan complete")
        void harmlessDeploymentRowsDoNotBlockThePurge() throws Exception {
            noDescriptors();

            DeploymentInfo noAgentId = new DeploymentInfo();
            noAgentId.setAgentVersion(1);
            DeploymentInfo noVersion = new DeploymentInfo();
            noVersion.setAgentId(DEPLOYED_AGENT_ID);

            String workflowlessAgentId = "aabbccddeeff112233445582";
            String goneAgentId = "aabbccddeeff112233445583";

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(noAgentId, noVersion, deployment(workflowlessAgentId, 1), deployment(goneAgentId, 7)));
            // Declares no workflows at all. Explicitly null, which is what a stored
            // document without the field deserializes to — the field defaults to an
            // empty list, so only this reaches the null branch.
            AgentConfiguration workflowless = new AgentConfiguration();
            workflowless.setWorkflows(null);
            when(agentStore.read(workflowlessAgentId, 1)).thenReturn(workflowless);
            // The deployed version is already gone from the store: it protects nothing,
            // and that is not an incomplete scan.
            when(agentStore.read(goneAgentId, 7)).thenThrow(new IResourceStore.ResourceNotFoundException("purged"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete(), "a stale deployment row must not veto the purge, got: " + report.getScanWarning());
            assertNull(report.getScanWarning());
        }

        @Test
        @DisplayName("a deployed Agent that cannot be read makes the scan incomplete and names it")
        void unreadableDeployedAgentMakesTheScanIncomplete() throws Exception {
            noDescriptors();
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployment(DEPLOYED_AGENT_ID, 4)));
            when(agentStore.read(DEPLOYED_AGENT_ID, 4)).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertFalse(report.isScanComplete());
            assertTrue(report.getScanWarning().contains(DEPLOYED_AGENT_ID),
                    "the warning must name the Agent whose references are missing, got: " + report.getScanWarning());
            assertEquals(409, assertThrows(WebApplicationException.class, () -> restOrphanAdmin.purgeOrphans(false))
                    .getResponse().getStatus());
        }

        @Test
        @DisplayName("a deployed workflow that no longer exists leaves the scan complete")
        void missingDeployedWorkflowLeavesTheScanComplete() throws Exception {
            noDescriptors();
            AgentConfiguration deployedAgent = new AgentConfiguration();
            deployedAgent.setWorkflows(List.of(DEPLOYED_WORKFLOW_URI));
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployment(DEPLOYED_AGENT_ID, 1)));
            when(agentStore.read(DEPLOYED_AGENT_ID, 1)).thenReturn(deployedAgent);
            when(workflowStore.read(DEPLOYED_WORKFLOW_ID, 3)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete(), "a workflow that is already gone protects nothing, got: " + report.getScanWarning());
        }

        @Test
        @DisplayName("a deployed workflow reference with no usable version is skipped, not fatal")
        void unversionedDeployedWorkflowReferenceIsSkipped() throws Exception {
            noDescriptors();
            AgentConfiguration deployedAgent = new AgentConfiguration();
            deployedAgent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + DEPLOYED_WORKFLOW_ID)));
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployment(DEPLOYED_AGENT_ID, 1)));
            when(agentStore.read(DEPLOYED_AGENT_ID, 1)).thenReturn(deployedAgent);

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete(), "an unusable reference is not a read failure, got: " + report.getScanWarning());
            verify(workflowStore, never()).read(eq(DEPLOYED_WORKFLOW_ID), anyInt());
        }

        @Test
        @DisplayName("a deployed workflow that cannot be read makes the scan incomplete and names it")
        void unreadableDeployedWorkflowMakesTheScanIncomplete() throws Exception {
            noDescriptors();
            AgentConfiguration deployedAgent = new AgentConfiguration();
            deployedAgent.setWorkflows(List.of(DEPLOYED_WORKFLOW_URI));
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployment(DEPLOYED_AGENT_ID, 1)));
            when(agentStore.read(DEPLOYED_AGENT_ID, 1)).thenReturn(deployedAgent);
            when(workflowStore.read(DEPLOYED_WORKFLOW_ID, 3)).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertFalse(report.isScanComplete(), "its extension references are missing from the set");
            assertTrue(report.getScanWarning().contains(DEPLOYED_WORKFLOW_ID),
                    "the warning must name the workflow, got: " + report.getScanWarning());
        }

        /**
         * The purge re-checks each candidate immediately before the irreversible
         * delete. A candidate it cannot even address — no version to ask about — is not
         * a licence to delete: fail closed, exactly as the reference check does.
         */
        @Test
        @DisplayName("an orphan with no usable version is not purged")
        void orphanWithoutAUsableVersionIsNotPurged() throws Exception {
            String ruleSetId = "aabbccddeeff112233445584";
            URI unversioned = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId);

            noDescriptors();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(unversioned, "unversioned-orphan")));
            // No live version either, so nothing supplies one.
            when(resourceClientLibrary.getCurrentResourceId(unversioned)).thenReturn(null);

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount(), "a candidate that cannot be re-checked must not be counted as purged");
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        /**
         * A descriptor whose URI carries no {@code ?version=} at all — hand-written, or
         * imported from an older export. Once the live version is known, both the
         * re-check and the delete must be addressed AT that version.
         *
         * <p>
         * The reverse-lookup stub below is the real {@code WorkflowStore} contract, not
         * a convenience: {@code WorkflowStore.getWorkflowDescriptorsContainingResource}
         * throws {@code ResourceStoreException("Reverse lookup requires a versioned
         * resource URI")} for exactly the query-less string, and
         * {@code ResourceClientLibrary.deleteResource} reads the version straight out
         * of the URI it is handed. So a test that stubs the lookup with
         * {@code anyString()} certifies a purge the deployment cannot perform: in
         * production {@code isReferencedNow} catches, logs "NOT purging it" and answers
         * true, and the orphan is re-listed by every scan for ever. Pinning the
         * VERSIONED URI on both calls is what makes this test able to fail.
         * </p>
         */
        @Test
        @DisplayName("an unversioned descriptor URI is re-addressed at the live version before it is purged")
        void unversionedDescriptorUriIsPurgedVerbatim() throws Exception {
            String ruleSetId = "aabbccddeeff112233445585";
            URI unversioned = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId);
            URI atLiveVersion = URI.create(unversioned + "?version=4");

            noDescriptors();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(unversioned, "live-but-unreferenced")));
            when(resourceClientLibrary.getCurrentResourceId(unversioned)).thenReturn(resourceId(ruleSetId, 4));
            // The real store's contract: a versioned URI is answered, anything else is
            // refused.
            when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean()))
                    .thenAnswer(invocation -> {
                        String uri = invocation.getArgument(0);
                        if (RestUtilities.pathWithoutVersionQuery(URI.create(uri)) == null) {
                            throw new IResourceStore.ResourceStoreException(
                                    "Reverse lookup requires a versioned resource URI ('...?version=<n>' with n >= 1), got: " + uri);
                        }
                        return List.of();
                    });

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getDeletedCount(), "a resolvable, unreferenced orphan must actually converge");
            verify(workflowStore).getWorkflowDescriptorsContainingResource(atLiveVersion.toString(), true);
            verify(resourceClientLibrary).deleteResource(atLiveVersion, true);
            verify(resourceClientLibrary, never()).deleteResource(eq(unversioned), anyBoolean());
            verify(documentDescriptorStore).deleteAllDescriptor(ruleSetId);
        }

        /**
         * {@code IWorkflowStore} reports "no live version" by exception where
         * {@code ResourceClientLibrary} reports it by null. The purge has to read both
         * as the same thing, or an already soft-deleted workflow whose history is being
         * purged throws out of the loop instead of being addressed at the version its
         * descriptor carries.
         */
        @Test
        @DisplayName("a workflow with no live version falls back to the descriptor's version")
        void workflowWithoutALiveVersionFallsBackToTheDescriptor() throws Exception {
            String workflowId = "aabbccddeeff112233445586";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=2");

            noDescriptors();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "soft-deleted-workflow")));
            when(workflowStore.read(eq(workflowId), anyInt())).thenReturn(emptyWorkflow());
            when(workflowStore.getCurrentResourceId(workflowId)).thenThrow(new IResourceStore.ResourceNotFoundException("soft-deleted"));
            when(agentStore.getAgentDescriptorsContainingWorkflow(workflowId, 2, true)).thenReturn(List.of());
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            OrphanReport report = restOrphanAdmin.purgeOrphans(true);

            assertEquals(1, report.getDeletedCount());
            verify(restWorkflowStore).deleteWorkflow(workflowId, 2, true, false);
        }

        /**
         * The resource IS gone by the time the descriptor is removed, so a descriptor
         * that cannot be deleted must not turn a completed purge into a failure — it
         * only means this resource will be re-reported on the next sweep, which the
         * WARN says.
         */
        @Test
        @DisplayName("a descriptor that cannot be removed does not un-count a completed purge")
        void descriptorRemovalFailureDoesNotUncountThePurge() throws Exception {
            String ruleSetId = "aabbccddeeff112233445587";
            URI ruleSetUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/" + ruleSetId + "?version=1");

            noDescriptors();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(ruleSetUri, "orphan-ruleset")));
            when(resourceClientLibrary.getCurrentResourceId(ruleSetUri)).thenReturn(resourceId(ruleSetId, 1));
            when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean())).thenReturn(List.of());
            doThrow(new IllegalStateException("descriptor store down"))
                    .when(documentDescriptorStore).deleteAllDescriptor(ruleSetId);

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getDeletedCount(), "the resource was deleted; the descriptor is bookkeeping");
            verify(resourceClientLibrary).deleteResource(ruleSetUri, true);
        }

        /**
         * A descriptor whose resource URI carries no authority at all cannot be keyed
         * by canonical type; it is compared verbatim instead. That protects nothing —
         * but it must not throw, and a workflow step written the same way must still
         * match it, or the scan promotes a referenced resource to "orphan".
         */
        @Test
        @DisplayName("a hostless resource URI is compared verbatim rather than throwing")
        void hostlessResourceUriIsComparedVerbatim() throws Exception {
            String ruleSetId = "aabbccddeeff112233445588";
            URI hostless = URI.create("rulestore/rulesets/" + ruleSetId + "?version=1");
            String workflowId = "aabbccddeeff112233445589";
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1");

            WorkflowConfiguration workflowConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.behavior"));
            step.setConfig(new HashMap<>(Map.of("uri", hostless.toString())));
            workflowConfig.setWorkflowSteps(List.of(step));

            noDescriptors();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(workflowUri, "workflow-with-a-relative-reference")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(AGENT_URI, "live-agent")));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor(hostless, "referenced-by-a-relative-uri")));
            AgentConfiguration agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(workflowUri));
            when(agentStore.read(eq(AGENT_ID), any())).thenReturn(agentConfig);
            when(workflowStore.read(eq(workflowId), any())).thenReturn(workflowConfig);

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertTrue(report.isScanComplete(), "a hostless URI is not a scan failure, got: " + report.getScanWarning());
            assertTrue(report.getOrphans().stream().noneMatch(o -> hostless.equals(o.getResourceUri())),
                    "the verbatim key must still match the step that references it, got: " + report.getOrphans());
        }
    }
}
