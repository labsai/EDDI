package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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

    private RestAgentStore restAgentStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restAgentStore = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore);
    }

    /** Helper to create a dummy DocumentDescriptor for reference-count mocking */
    private DocumentDescriptor dummyDescriptor() {
        return new DocumentDescriptor();
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
        @DisplayName("should cascade-delete packages when cascade=true and packages are not shared")
        void deleteAgent_cascade() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // Each package is only referenced by this one agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, false)).thenReturn(List.of(dummyDescriptor()));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, true, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, true, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should skip cascade-delete of packages shared with other agents")
        void deleteAgent_cascade_skipsSharedWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // PKG1 is shared with 2 agents — should be SKIPPED
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, false)).thenReturn(List.of(dummyDescriptor(), dummyDescriptor()));
            // PKG2 is only in this Agent — should be deleted
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, false)).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // Only PKG2 should be deleted — PKG1 is shared
            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, true, true);
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
            when(AgentStore.getAgentDescriptorsContainingWorkflow(anyString(), anyInt(), eq(false))).thenReturn(List.of(dummyDescriptor()));
            when(restWorkflowStore.deleteWorkflow(PKG1_ID, 1, true, true)).thenThrow(new RuntimeException("Workflow in use"));
            when(restWorkflowStore.deleteWorkflow(PKG2_ID, 1, true, true)).thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 1, true, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, true, true);
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
}
