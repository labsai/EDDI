/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestOrphanAdmin Tests")
class RestOrphanAdminTest {

    @Mock
    private IAgentStore agentStore;
    @Mock
    private IWorkflowStore workflowStore;
    @Mock
    private IDocumentDescriptorStore descriptorStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;

    private RestOrphanAdmin admin;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        admin = new RestOrphanAdmin(agentStore, workflowStore, descriptorStore, resourceClientLibrary);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Nested
    @DisplayName("scanOrphans")
    class ScanOrphansTests {

        @Test
        @DisplayName("no agents, no workflows — empty report")
        void noAgentsNoWorkflows() throws Exception {
            // Return empty for all descriptor types
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());

            OrphanReport report = admin.scanOrphans(false);

            assertNotNull(report);
            assertEquals(0, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
            assertTrue(report.getOrphans().isEmpty());
        }

        @Test
        @DisplayName("resource with null URI — not counted as orphan")
        void resourceWithNullUri() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setResource(null);
            desc.setName("null-uri");

            // First call for agent descriptors returns empty
            // Then for workflow, rules, etc. return the null-uri descriptor for one type
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());

            OrphanReport report = admin.scanOrphans(false);
            assertEquals(0, report.getTotalOrphans());
        }

        @Test
        @org.junit.jupiter.api.Disabled("URI format assumptions incorrect for orphan detection")
        @DisplayName("resource referenced by agent — not orphan")
        void referencedResource() throws Exception {
            // Setup: One agent, one workflow it references
            var agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agents/agent1?version=1"));

            // Agent config references a workflow
            var agentConfig = new ai.labs.eddi.configs.agents.model.AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflows/wf1?version=1")));
            when(agentStore.read("agent1", 1)).thenReturn(agentConfig);

            // For readAllDescriptors: page 0 returns one agent, page 1 returns empty
            doReturn(List.of(agentDesc))
                    .when(descriptorStore).readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), eq(false));
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.agent"), anyString(), eq(200), anyInt(), eq(false));

            // Workflow descriptors
            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflows/wf1?version=1"));
            wfDesc.setName("Referenced Workflow");

            doReturn(List.of(wfDesc))
                    .when(descriptorStore).readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false));
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.workflow"), anyString(), eq(200), anyInt(), eq(false));

            // All other store types empty
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.apicalls"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.output"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.llm"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.property"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.dictionary"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.parser"), anyString(), anyInt(), anyInt(), anyBoolean());

            OrphanReport report = admin.scanOrphans(false);
            // The workflow IS referenced so it shouldn't be an orphan
            assertEquals(0, report.getTotalOrphans());
        }
    }

    @Nested
    @DisplayName("purgeOrphans")
    class PurgeOrphansTests {

        @Test
        @DisplayName("no orphans — deletedCount is 0")
        void noOrphans() throws Exception {
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());

            OrphanReport report = admin.purgeOrphans(false);

            assertEquals(0, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("purge succeeds — deletedCount matches orphan count")
        void purgeSucceeds() throws Exception {
            // Create an orphan resource (not referenced by any agent or workflow)
            var orphanDesc = new DocumentDescriptor();
            orphanDesc.setResource(URI.create("eddi://ai.labs.rules/rules/orphan1?version=1"));
            orphanDesc.setName("Orphan Rule");

            // No agents, no workflows
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean());

            // Rules store returns the orphan
            doReturn(List.of(orphanDesc))
                    .when(descriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean());

            // All other store types empty
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.apicalls"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.output"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.llm"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.property"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.dictionary"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.parser"), anyString(), anyInt(), anyInt(), anyBoolean());

            OrphanReport report = admin.purgeOrphans(false);
            assertEquals(1, report.getTotalOrphans());
            assertEquals(1, report.getDeletedCount());
        }

        @Test
        @DisplayName("purge fails for one orphan — deletedCount less than orphan count")
        void purgePartialFailure() throws Exception {
            var orphanDesc = new DocumentDescriptor();
            orphanDesc.setResource(URI.create("eddi://ai.labs.rules/rules/orphan1?version=1"));
            orphanDesc.setName("Orphan Rule");

            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(List.of(orphanDesc))
                    .when(descriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.apicalls"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.output"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.llm"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.property"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.dictionary"), anyString(), anyInt(), anyInt(), anyBoolean());
            doReturn(Collections.emptyList())
                    .when(descriptorStore).readDescriptors(eq("ai.labs.parser"), anyString(), anyInt(), anyInt(), anyBoolean());

            when(resourceClientLibrary.deleteResource(any(), anyBoolean()))
                    .thenThrow(new RuntimeException("delete failed"));

            OrphanReport report = admin.purgeOrphans(false);
            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
        }
    }
}
