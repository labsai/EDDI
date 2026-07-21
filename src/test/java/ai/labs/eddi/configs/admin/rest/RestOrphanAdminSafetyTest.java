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
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private RestOrphanAdmin restOrphanAdmin;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restOrphanAdmin = new RestOrphanAdmin(agentStore, workflowStore, documentDescriptorStore, resourceClientLibrary);
    }

    private static DocumentDescriptor descriptor(URI resource, String name) {
        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName(name);
        return descriptor;
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
    }
}
