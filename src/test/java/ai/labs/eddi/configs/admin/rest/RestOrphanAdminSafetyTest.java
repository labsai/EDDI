/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
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

    private RestOrphanAdmin restOrphanAdmin;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restOrphanAdmin = new RestOrphanAdmin(agentStore, workflowStore, documentDescriptorStore, resourceClientLibrary, restWorkflowStore);
    }

    private static DocumentDescriptor descriptor(URI resource, String name) {
        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName(name);
        return descriptor;
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
}
