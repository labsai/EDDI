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
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestOrphanAdmin — Branch Coverage Tests")
class RestOrphanAdminBranchTest {

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

    // =========================================================
    // scanOrphans
    // =========================================================

    @Nested
    @DisplayName("scanOrphans")
    class ScanOrphans {

        @Test
        @DisplayName("no agents/workflows — no orphans found")
        void noAgentsNoWorkflows() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);

            assertEquals(0, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
            assertNotNull(report.getOrphans());
            assertTrue(report.getOrphans().isEmpty());
        }

        @Test
        @DisplayName("resource referenced by agent is NOT an orphan")
        void referencedResourceNotOrphan() throws Exception {
            // Agent descriptor
            var agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccddeeff112233445566?version=1"));
            agentDesc.setName("Test Agent");

            // Agent config with a workflow URI
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1")));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(agentDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(agentStore.read("aabbccddeeff112233445566", 1)).thenReturn(agentConfig);

            // Workflow descriptor (same URI as referenced)
            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));
            wfDesc.setName("Test Workflow");

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            // Workflow config
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of());
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            // All other types return empty
            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);
            assertEquals(0, report.getTotalOrphans());
        }

        @Test
        @DisplayName("unreferenced resource IS an orphan")
        void unreferencedResourceIsOrphan() throws Exception {
            // No agents or workflows
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            // A rules descriptor that is NOT referenced
            var rulesDesc = new DocumentDescriptor();
            rulesDesc.setResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445568?version=1"));
            rulesDesc.setName("Orphan Rule");

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(rulesDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            // Other types empty
            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);
            assertEquals(1, report.getTotalOrphans());
            assertEquals("ai.labs.rules", report.getOrphans().get(0).getType());
        }

        @Test
        @DisplayName("descriptor with null resource URI is skipped")
        void nullResourceUriSkipped() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var desc = new DocumentDescriptor();
            desc.setResource(null); // null resource
            desc.setName("No Resource");

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(desc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);
            assertEquals(0, report.getTotalOrphans());
        }

        @Test
        @DisplayName("descriptor with null name shows (unnamed)")
        void nullNameShowsUnnamed() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445568?version=1"));
            desc.setName(null);

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(desc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);
            assertEquals(1, report.getTotalOrphans());
            assertEquals("(unnamed)", report.getOrphans().get(0).getName());
        }

        @Test
        @DisplayName("exception during store scan is handled gracefully")
        void exceptionDuringScan() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            // Throw for one type
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenThrow(new RuntimeException("scan error"));

            // Other types empty
            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.scanOrphans(false);
            assertNotNull(report);
        }

        @Test
        @DisplayName("agent with null workflows list does not throw")
        void agentWithNullWorkflows() throws Exception {
            var agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccddeeff112233445566?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(agentDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(null);
            when(agentStore.read("aabbccddeeff112233445566", 1)).thenReturn(agentConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("agent ResourceNotFoundException is handled")
        void agentResourceNotFound() throws Exception {
            var agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccddeeff112233445566?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(agentDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            when(agentStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("agent with generic exception during read is handled")
        void agentGenericException() throws Exception {
            var agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/aabbccddeeff112233445566?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(agentDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            when(agentStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new RuntimeException("read error"));

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("workflow with extension URI in config")
        void workflowWithExtensionUri() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            // Build workflow config with extension URI
            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/rule1?version=1"));
            step.setExtensions(null);

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("workflow with dictionaries extension nested URIs")
        void workflowWithDictionariesExtension() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            // Build workflow config with nested dictionaries
            var dictConfig = new HashMap<String, Object>();
            dictConfig.put("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1");

            var dictEntry = new HashMap<String, Object>();
            dictEntry.put("config", dictConfig);

            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/parser1?version=1"));
            step.setExtensions(Map.of("dictionaries", List.of(dictEntry)));

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("workflow with null config on step")
        void workflowWithNullConfig() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var step = new WorkflowStep();
            step.setConfig(null);
            step.setExtensions(null);

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("workflow ResourceNotFoundException is handled")
        void workflowResourceNotFound() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            when(workflowStore.read("aabbccddeeff112233445567", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("dictionaries entry not a Map is skipped")
        void dictionariesEntryNotMap() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/p1?version=1"));
            // dictionaries contains a String instead of a Map
            step.setExtensions(Map.of("dictionaries", List.of("not-a-map")));

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("dictionaries entry config is not a Map")
        void dictionariesConfigNotMap() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            // config is a String, not a Map
            var dictEntry = new HashMap<String, Object>();
            dictEntry.put("config", "not-a-map");

            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/p1?version=1"));
            step.setExtensions(Map.of("dictionaries", List.of(dictEntry)));

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }

        @Test
        @DisplayName("dictionaries is not a List")
        void dictionariesNotList() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/p1?version=1"));
            step.setExtensions(Map.of("dictionaries", "not-a-list"));

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }
    }

    // =========================================================
    // purgeOrphans
    // =========================================================

    @Nested
    @DisplayName("purgeOrphans")
    class PurgeOrphans {

        @Test
        @DisplayName("successfully purges orphans")
        void successfulPurge() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445568?version=1"));
            desc.setName("Orphan");

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(desc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(1, report.getDeletedCount());
            verify(resourceClientLibrary).deleteResource(any(URI.class), eq(true));
        }

        @Test
        @DisplayName("purge handles deletion failure gracefully")
        void purgeHandlesDeletionFailure() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445568?version=1"));
            desc.setName("Orphan");

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(0), anyInt(), anyBoolean()))
                    .thenReturn(List.of(desc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.rules"), anyString(), eq(1), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow") && !t.equals("ai.labs.rules")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            doThrow(new RuntimeException("delete failed"))
                    .when(resourceClientLibrary).deleteResource(any(URI.class), eq(true));

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(1, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
        }

        @Test
        @DisplayName("purge with no orphans has zero counts")
        void purgeNoOrphans() throws Exception {
            when(documentDescriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            OrphanReport report = restOrphanAdmin.purgeOrphans(false);

            assertEquals(0, report.getTotalOrphans());
            assertEquals(0, report.getDeletedCount());
        }
    }

    // =========================================================
    // scanReferencedUris — exception handling
    // =========================================================

    @Nested
    @DisplayName("scanReferencedUris — error handling")
    class BuildReferencedUrisSetErrors {

        @Test
        @DisplayName("global exception in scanReferencedUris is caught")
        void globalException() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenThrow(new IResourceStore.ResourceStoreException("db error"));

            // Other store types should still be scanned
            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }
    }

    // =========================================================
    // collectExtensionUris — edge cases
    // =========================================================

    @Nested
    @DisplayName("collectExtensionUris — edge cases")
    class CollectExtensionUrisEdge {

        @Test
        @DisplayName("step with empty uri string in config")
        void emptyUriInConfig() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var wfDesc = new DocumentDescriptor();
            wfDesc.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabbccddeeff112233445567?version=1"));

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(0), anyInt(), eq(false)))
                    .thenReturn(List.of(wfDesc));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.workflow"), anyString(), eq(1), anyInt(), eq(false)))
                    .thenReturn(List.of());

            var step = new WorkflowStep();
            step.setConfig(Map.of("uri", ""));
            step.setExtensions(null);

            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.read("aabbccddeeff112233445567", 1)).thenReturn(wfConfig);

            when(documentDescriptorStore.readDescriptors(
                    argThat(t -> t != null && !t.equals("ai.labs.agent") && !t.equals("ai.labs.workflow")),
                    anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> restOrphanAdmin.scanOrphans(false));
        }
    }
}
