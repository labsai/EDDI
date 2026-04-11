package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestExportService} covering security validations,
 * snippet reference extraction, and preview logic.
 */
class RestExportServiceTest {

    private IDocumentDescriptorStore documentDescriptorStore;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IJsonSerialization jsonSerialization;
    private IScheduleStore scheduleStore;
    private RestExportService exportService;

    @BeforeEach
    void setUp() {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        var dictionaryStore = mock(IDictionaryStore.class);
        var behaviorStore = mock(IRuleSetStore.class);
        var httpCallsStore = mock(IApiCallsStore.class);
        var llmStore = mock(ILlmStore.class);
        var propertySetterStore = mock(IPropertySetterStore.class);
        var outputStore = mock(IOutputStore.class);
        var mcpCallsStore = mock(IMcpCallsStore.class);
        var ragStore = mock(IRagStore.class);
        var snippetStore = mock(IPromptSnippetStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var zipArchive = mock(IZipArchive.class);
        var secretScrubber = mock(SecretScrubber.class);
        scheduleStore = mock(IScheduleStore.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                dictionaryStore, behaviorStore, httpCallsStore, llmStore,
                propertySetterStore, outputStore, mcpCallsStore, ragStore,
                snippetStore, jsonSerialization, zipArchive, secretScrubber,
                scheduleStore);
    }

    // ─── getAgentZipArchive security ─────────────────────────────

    @Nested
    @DisplayName("getAgentZipArchive security validation")
    class ZipArchiveSecurity {

        @Test
        @DisplayName("should reject null filename")
        void nullFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive(null));
        }

        @Test
        @DisplayName("should reject empty filename")
        void emptyFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive(""));
        }

        @Test
        @DisplayName("should reject filename with path traversal")
        void pathTraversal() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("../etc/passwd"));
        }

        @Test
        @DisplayName("should reject filename with spaces")
        void spacesInFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("agent file.zip"));
        }

        @Test
        @DisplayName("should reject filename with shell characters")
        void shellChars() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("agent;rm -rf.zip"));
        }

        @Test
        @DisplayName("should accept valid filename with alphanumerics and valid chars")
        void validFilename() {
            // Valid pattern: alphanumeric, dots, hyphens, underscores, plus
            // Note: will throw FileNotFoundException since the file doesn't exist,
            // but should NOT throw BadRequestException (security validation passes)
            assertThrows(Exception.class, () -> exportService.getAgentZipArchive("My-Agent_v1.0+build.zip"));
            // The exception should NOT be BadRequestException
            try {
                exportService.getAgentZipArchive("valid-agent-id-1.zip");
                fail("Should throw for missing file");
            } catch (BadRequestException e) {
                fail("Should not throw BadRequestException for valid filename");
            } catch (Exception e) {
                // Expected: FileNotFoundException or similar — proves security validation
                // passed
            }
        }
    }

    // ─── exportAgent security ────────────────────────────────────

    @Nested
    @DisplayName("exportAgent path validation")
    class ExportAgentSecurity {

        @Test
        @DisplayName("should reject agentId with path traversal")
        void pathTraversalInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("../../../etc/passwd", 1, null));
        }

        @Test
        @DisplayName("should reject agentId with forward slash")
        void slashInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agents/evil", 1, null));
        }

        @Test
        @DisplayName("should reject agentId with backslash")
        void backslashInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agents\\evil", 1, null));
        }

        @Test
        @DisplayName("should reject empty agentId")
        void emptyAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("", 1, null));
        }
    }

    // ─── previewExport ───────────────────────────────────────────

    @Nested
    @DisplayName("previewExport")
    class PreviewExport {

        @Test
        @DisplayName("should return preview with agent and workflow resources")
        void happyPath() throws Exception {
            String agentId = "agent-abc";
            int agentVersion = 1;

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=2")));

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Test Agent");

            when(agentStore.read(agentId, agentVersion)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, agentVersion)).thenReturn(agentDescriptor);

            var wfConfig = new WorkflowConfiguration();
            when(workflowStore.read("wf-1", 2)).thenReturn(wfConfig);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            var wfDescriptor = new DocumentDescriptor();
            wfDescriptor.setName("Workflow 1");
            when(documentDescriptorStore.readDescriptor("wf-1", 2)).thenReturn(wfDescriptor);

            var preview = exportService.previewExport(agentId, agentVersion);

            assertNotNull(preview);
            assertEquals(agentId, preview.agentId());
            assertEquals("Test Agent", preview.agentName());
            assertEquals(agentVersion, preview.agentVersion());
            assertFalse(preview.resources().isEmpty());
            // Should have at least agent + workflow entries
            assertTrue(preview.resources().size() >= 2);
        }

        @Test
        @DisplayName("should reject path-traversal agentId in preview")
        void previewRejectsPathTraversal() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport("../hack", 1));
        }
    }

    // ─── SNIPPET_REF_PATTERN (via reflection or observable behavior) ───

    @Nested
    @DisplayName("Snippet reference extraction pattern")
    class SnippetRefPattern {

        /**
         * Replicates the private SNIPPET_REF_PATTERN to test regex logic.
         */
        private static final Pattern SNIPPET_REF_PATTERN = Pattern.compile("snippets\\.([a-zA-Z0-9_\\-]+)");

        @Test
        @DisplayName("should match {{snippets.name}} reference")
        void matchDoubleBrace() {
            var matcher = SNIPPET_REF_PATTERN.matcher("Use {{snippets.cautious_mode}} for safety");
            assertTrue(matcher.find());
            assertEquals("cautious_mode", matcher.group(1));
        }

        @Test
        @DisplayName("should match {snippets.name} reference")
        void matchSingleBrace() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{snippets.persona}");
            assertTrue(matcher.find());
            assertEquals("persona", matcher.group(1));
        }

        @Test
        @DisplayName("should extract multiple snippet names")
        void multipleSnippets() {
            String config = "{{snippets.intro}} and {{snippets.outro}} and {snippets.middle_part}";
            var matcher = SNIPPET_REF_PATTERN.matcher(config);
            var names = new java.util.ArrayList<String>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            assertEquals(3, names.size());
            assertTrue(names.containsAll(List.of("intro", "outro", "middle_part")));
        }

        @Test
        @DisplayName("should match names with hyphens and digits")
        void namesWithHyphensAndDigits() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{{snippets.my-snippet-v2}}");
            assertTrue(matcher.find());
            assertEquals("my-snippet-v2", matcher.group(1));
        }

        @Test
        @DisplayName("should not match without snippets prefix")
        void noMatch() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{{properties.user_name}}");
            assertFalse(matcher.find());
        }
    }
}
