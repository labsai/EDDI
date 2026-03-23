package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.model.OrphanInfo;
import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import jakarta.ws.rs.core.Response;
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

class RestOrphanAdminTest {

        // Realistic IDs (extractResourceId requires 18+ hex chars)
        private static final String AGENT1_ID = "aabbccddee1122334455";
        private static final String PKG1_ID = "ff00112233445566aa77";
        private static final String BEH1_ID = "1100aabbcc2233445566";
        private static final String ORPHAN_PKG_ID = "cc11223344556677aabb";
        private static final String ORPHAN_BEH_ID = "dd11223344556677aabb";

        @Mock
        private IAgentStore AgentStore;
        @Mock
        private IWorkflowStore WorkflowStore;
        @Mock
        private IDocumentDescriptorStore documentDescriptorStore;
        @Mock
        private IResourceClientLibrary resourceClientLibrary;

        private RestOrphanAdmin restOrphanAdmin;

        @BeforeEach
        void setUp() {
                openMocks(this);
                restOrphanAdmin = new RestOrphanAdmin(
                                AgentStore, WorkflowStore, documentDescriptorStore, resourceClientLibrary);
        }

        private DocumentDescriptor descriptor(String type, String id, int version, String name) {
                DocumentDescriptor dd = new DocumentDescriptor();
                dd.setResource(URI.create("eddi://" + uriType(type) + "/" + storePath(type) + "/" + id + "?version=" + version));
                dd.setName(name);
                dd.setDeleted(false);
                return dd;
        }

        /**
         * Maps descriptor query types to the correct URI scheme type.
         * The document store queries by type (e.g., "ai.labs.package") but
         * the resourceURI scheme may differ (e.g., "ai.labs.workflow").
         */
        private String uriType(String descriptorType) {
                return switch (descriptorType) {
                        case "ai.labs.package" -> "ai.labs.workflow";
                        default -> descriptorType;
                };
        }

        private String storePath(String type) {
                return switch (type) {
                        case "ai.labs.agent" -> "agentstore/agents";
                        case "ai.labs.package" -> "workflowstore/workflows";
                        case "ai.labs.behavior" -> "rulestore/rulesets";
                        case "ai.labs.httpcalls" -> "apicallstore/apicalls";
                        case "ai.labs.output" -> "outputstore/outputsets";
                        case "ai.labs.llm" -> "llmstore/llmconfigs";
                        case "ai.labs.property" -> "propertysetterstore/propertysetters";
                        case "ai.labs.regulardictionary" -> "dictionarystore/dictionaries";
                        case "ai.labs.parser" -> "parserstore/parsers";
                        default -> "unknown";
                };
        }

        @Nested
        @DisplayName("scanOrphans")
        class ScanOrphansTests {

                @Test
                @DisplayName("should return empty report when no orphans exist")
                void scanOrphans_noOrphans() throws Exception {
                        // One Agent referencing one package
                        DocumentDescriptor agentDesc = descriptor("ai.labs.agent", AGENT1_ID, 1, "TestAgent");
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(List.of(agentDesc))
                                        .thenReturn(Collections.emptyList());

                        AgentConfiguration agentConfig = new AgentConfiguration();
                        agentConfig.setWorkflows(new ArrayList<>(List.of(
                                        URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID
                                                        + "?version=1"))));
                        when(AgentStore.read(AGENT1_ID, 1)).thenReturn(agentConfig);

                        // One package referencing one behavior
                        DocumentDescriptor pkgDesc = descriptor("ai.labs.package", PKG1_ID, 1, "TestPkg");
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.package"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(List.of(pkgDesc))
                                        .thenReturn(Collections.emptyList());

                        WorkflowConfiguration pkgConfig = new WorkflowConfiguration();
                        WorkflowStep ext = new WorkflowStep();
                        ext.setType(URI.create("eddi://ai.labs.behavior"));
                        ext.setConfig(new HashMap<>(Map.of(
                                        "uri", "eddi://ai.labs.behavior/rulestore/rulesets/" + BEH1_ID
                                                        + "?version=1")));
                        pkgConfig.getWorkflowSteps().add(ext);
                        when(WorkflowStore.read(PKG1_ID, 1)).thenReturn(pkgConfig);

                        // Store scans: package store has the one referenced package, behavior store has
                        // the one referenced behavior
                        // All other stores are empty
                        setupStoreReturns(Map.of(
                                        "ai.labs.package", List.of(pkgDesc),
                                        "ai.labs.behavior",
                                        List.of(descriptor("ai.labs.behavior", BEH1_ID, 1, "TestBeh"))));

                        OrphanReport report = restOrphanAdmin.scanOrphans(false);

                        assertEquals(0, report.getTotalOrphans());
                        assertEquals(0, report.getDeletedCount());
                        assertTrue(report.getOrphans().isEmpty());
                }

                @Test
                @DisplayName("should detect orphaned packages and extensions")
                void scanOrphans_findsOrphans() throws Exception {
                        // One Agent referencing only PKG1 (PKG2 is orphaned)
                        DocumentDescriptor agentDesc = descriptor("ai.labs.agent", AGENT1_ID, 1, "TestAgent");
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(List.of(agentDesc))
                                        .thenReturn(Collections.emptyList());

                        AgentConfiguration agentConfig = new AgentConfiguration();
                        agentConfig.setWorkflows(new ArrayList<>(List.of(
                                        URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID
                                                        + "?version=1"))));
                        when(AgentStore.read(AGENT1_ID, 1)).thenReturn(agentConfig);

                        // PKG1 references BEH1 (so BEH1 is not orphaned), ORPHAN_BEH is not referenced
                        DocumentDescriptor pkgDesc = descriptor("ai.labs.package", PKG1_ID, 1, "UsedPkg");
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.package"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(List.of(pkgDesc))
                                        .thenReturn(Collections.emptyList());

                        WorkflowConfiguration pkgConfig = new WorkflowConfiguration();
                        WorkflowStep ext = new WorkflowStep();
                        ext.setType(URI.create("eddi://ai.labs.behavior"));
                        ext.setConfig(new HashMap<>(Map.of(
                                        "uri", "eddi://ai.labs.behavior/rulestore/rulesets/" + BEH1_ID
                                                        + "?version=1")));
                        pkgConfig.getWorkflowSteps().add(ext);
                        when(WorkflowStore.read(PKG1_ID, 1)).thenReturn(pkgConfig);

                        // Store scans: package store has PKG1 (used) + ORPHAN_PKG (orphan)
                        // Behavior store has BEH1 (used) + ORPHAN_BEH (orphan)
                        DocumentDescriptor orphanPkgDesc = descriptor("ai.labs.package", ORPHAN_PKG_ID, 1, "OrphanPkg");
                        DocumentDescriptor orphanBehDesc = descriptor("ai.labs.behavior", ORPHAN_BEH_ID, 1,
                                        "OrphanBeh");
                        setupStoreReturns(Map.of(
                                        "ai.labs.package", List.of(pkgDesc, orphanPkgDesc),
                                        "ai.labs.behavior", List.of(
                                                        descriptor("ai.labs.behavior", BEH1_ID, 1, "UsedBeh"),
                                                        orphanBehDesc)));

                        OrphanReport report = restOrphanAdmin.scanOrphans(false);

                        assertEquals(2, report.getTotalOrphans());
                        assertEquals(0, report.getDeletedCount()); // scan only, no purge

                        List<String> orphanTypes = report.getOrphans().stream()
                                        .map(OrphanInfo::getType).sorted().toList();
                        assertTrue(orphanTypes.contains("ai.labs.package"));
                        assertTrue(orphanTypes.contains("ai.labs.behavior"));
                }

                @Test
                @DisplayName("should return empty report when no agents exist")
                void scanOrphans_noAgents() throws Exception {
                        // No agents at all
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(Collections.emptyList());
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.package"), eq(""), anyInt(), anyInt(),
                                        eq(false)))
                                        .thenReturn(Collections.emptyList());

                        // All other stores also empty
                        setupStoreReturns(Map.of());

                        OrphanReport report = restOrphanAdmin.scanOrphans(false);

                        assertEquals(0, report.getTotalOrphans());
                }
        }

        @Nested
        @DisplayName("purgeOrphans")
        class PurgeOrphansTests {

                @Test
                @DisplayName("should delete orphans and return count")
                void purgeOrphans_deletesOrphans() throws Exception {
                        // One agent, no packages referenced
                        DocumentDescriptor agentDesc = descriptor("ai.labs.agent", AGENT1_ID, 1, "TestAgent");
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), eq(""), anyInt(), anyInt(),
                                        eq(true)))
                                        .thenReturn(List.of(agentDesc))
                                        .thenReturn(Collections.emptyList());

                        AgentConfiguration agentConfig = new AgentConfiguration();
                        agentConfig.setWorkflows(new ArrayList<>());
                        when(AgentStore.read(AGENT1_ID, 1)).thenReturn(agentConfig);

                        // One orphan package in the store
                        when(documentDescriptorStore.readDescriptors(eq("ai.labs.package"), eq(""), anyInt(), anyInt(),
                                        eq(true)))
                                        .thenReturn(List.of(
                                                        descriptor("ai.labs.package", ORPHAN_PKG_ID, 1, "OrphanPkg")))
                                        .thenReturn(Collections.emptyList());

                        // All other stores empty
                        for (String type : List.of("ai.labs.behavior", "ai.labs.httpcalls", "ai.labs.output",
                                        "ai.labs.llm", "ai.labs.property", "ai.labs.regulardictionary",
                                        "ai.labs.parser")) {
                                when(documentDescriptorStore.readDescriptors(eq(type), eq(""), anyInt(), anyInt(),
                                                eq(true)))
                                                .thenReturn(Collections.emptyList());
                        }

                        when(resourceClientLibrary.deleteResource(any(), eq(true)))
                                        .thenReturn(Response.ok().build());

                        OrphanReport report = restOrphanAdmin.purgeOrphans(true);

                        assertEquals(1, report.getTotalOrphans());
                        assertEquals(1, report.getDeletedCount());
                        verify(resourceClientLibrary).deleteResource(any(), eq(true));
                }
        }

        /**
         * Helper to set up mock returns for all store types.
         * Unspecified types return empty lists.
         */
        private void setupStoreReturns(Map<String, List<DocumentDescriptor>> storeDescriptors) throws Exception {
                for (String type : List.of("ai.labs.package", "ai.labs.behavior", "ai.labs.httpcalls",
                                "ai.labs.output", "ai.labs.llm", "ai.labs.property",
                                "ai.labs.regulardictionary", "ai.labs.parser")) {
                        List<DocumentDescriptor> descs = storeDescriptors.getOrDefault(type, Collections.emptyList());
                        // First call returns the descriptors, subsequent calls return empty (pagination
                        // boundary)
                        when(documentDescriptorStore.readDescriptors(eq(type), eq(""), eq(0), anyInt(), eq(false)))
                                        .thenReturn(descs);
                        if (!descs.isEmpty()) {
                                when(documentDescriptorStore.readDescriptors(eq(type), eq(""), eq(descs.size()),
                                                anyInt(), eq(false)))
                                                .thenReturn(Collections.emptyList());
                        }
                }
        }
}
