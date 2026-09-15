/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snippets are global resources an archive carries alongside the agent, and
 * they are deliberately best-effort: a snippet store that is unreachable,
 * inconsistent, or holding a half-written document must cost the snippet, never
 * the agent the operator was actually restoring.
 * <p>
 * The preview obeys the same rule — a row it cannot build is a row it leaves
 * out, so the wizard still has something to show.
 */
@DisplayName("RestImportService — snippet resilience")
class RestImportServiceSnippetResilienceTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";
    private static final String EXISTING_SNIPPET_ID = "eeee11112222333344445555";
    private static final String CREATED_SNIPPET_ID = "ffff11112222333344445555";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IRestPromptSnippetStore restSnippetStore;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        restSnippetStore = mock(IRestPromptSnippetStore.class);

        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class));

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(PromptSnippet.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), PromptSnippet.class));
    }

    /**
     * A snippet whose name is missing has no natural key — nothing can be matched
     * or deduplicated against it — so it is left out of the preview rather than
     * shown as a row that cannot be acted on.
     */
    @Test
    @DisplayName("the preview lists no row for a nameless or unparseable snippet")
    void namelessAndUnparseableSnippetsAreNotPreviewed() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "nameless", "{\"content\":\"no name here\"}");
            writeSnippet(dir, "broken", "this is not json at all");
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });
        when(restSnippetStore.readSnippetDescriptors("", 0, 0)).thenReturn(List.of());

        ImportPreview preview = previewImport();

        List<String> snippetNames = preview.resources().stream()
                .filter(diff -> "snippet".equals(diff.resourceType()))
                .map(ResourceDiff::name)
                .toList();
        assertEquals(List.of("cautious_mode"), snippetNames,
                "only a snippet with a usable natural key may become a row");
    }

    /**
     * The preview's CREATE/UPDATE split comes from a name lookup over every
     * existing snippet. A descriptor that names no resource, and a snippet the
     * store cannot hand back, must each drop out of that lookup instead of aborting
     * it — otherwise one bad document turns every snippet in the archive into a
     * CREATE that then duplicates the ones already here.
     */
    @Test
    @DisplayName("a descriptor with no resource, and one the store cannot read, do not break the name lookup")
    void brokenDescriptorsDoNotBreakTheNameLookup() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });

        var noResource = new DocumentDescriptor();
        var unreadable = new DocumentDescriptor();
        unreadable.setResource(URI.create(
                "eddi://ai.labs.snippet/snippetstore/snippets/1111222233334444aaaabbbb?version=1"));
        var usable = new DocumentDescriptor();
        usable.setResource(URI.create(
                "eddi://ai.labs.snippet/snippetstore/snippets/" + EXISTING_SNIPPET_ID + "?version=2"));

        when(restSnippetStore.readSnippetDescriptors("", 0, 0))
                .thenReturn(List.of(noResource, unreadable, usable));
        doAnswer(inv -> {
            throw new IllegalStateException("snippet unreadable");
        }).when(restSnippetStore).readSnippet("1111222233334444aaaabbbb", 1);
        var existing = new PromptSnippet();
        existing.setName("cautious_mode");
        when(restSnippetStore.readSnippet(EXISTING_SNIPPET_ID, 2)).thenReturn(existing);

        ImportPreview preview = previewImport();

        ResourceDiff snippetRow = preview.resources().stream()
                .filter(diff -> "snippet".equals(diff.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no snippet row: " + preview.resources()));
        assertEquals(DiffAction.UPDATE, snippetRow.action(),
                "the readable descriptor must still be found, so the import updates rather than duplicates");
        assertEquals(EXISTING_SNIPPET_ID, snippetRow.targetId());
    }

    /**
     * A snippet store that will not list at all costs the CREATE/UPDATE split, and
     * nothing more: every archived snippet is previewed as new, which is the
     * conservative reading, and the agent rows are untouched.
     */
    @Test
    @DisplayName("a snippet store that cannot list still leaves the agent previewable")
    void unlistableSnippetStoreStillPreviewsTheAgent() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });
        doAnswer(inv -> {
            throw new IllegalStateException("snippet store unavailable");
        }).when(restSnippetStore).readSnippetDescriptors("", 0, 0);

        ImportPreview preview = previewImport();

        assertEquals(AGENT_ORIGIN_ID, preview.sourceAgentId());
        assertTrue(preview.resources().stream().anyMatch(diff -> "agent".equals(diff.resourceType())),
                "the agent row must survive a snippet store that will not answer");
        assertEquals(DiffAction.CREATE, preview.resources().stream()
                .filter(diff -> "snippet".equals(diff.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no snippet row: " + preview.resources()))
                .action());
    }

    /**
     * The import itself follows the same rule: a snippet surface that is not there
     * must not fail the restore of the agent that came with it.
     */
    @Test
    @DisplayName("an unreachable snippet surface does not fail the agent import")
    void unreachableSnippetSurfaceDoesNotFailTheImport() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });
        doAnswer(inv -> {
            throw new IllegalStateException("snippet store unavailable");
        }).when(restSnippetStore).readSnippetDescriptors("", 0, 0);
        doAnswer(inv -> {
            throw new IllegalStateException("snippet store unavailable");
        }).when(restSnippetStore).createSnippet(any());

        Response response = runImport();

        assertEquals(201, response.getStatus(),
                "the agent is what the operator asked to restore; a snippet must not take it down");
    }

    /**
     * A snippet this import created is as much an orphan as a workflow when a later
     * resource blows up, so it is recorded on the import transaction and deleted
     * again on rollback. The id comes from the {@code X-Resource-URI} header rather
     * than {@code Response.getLocation()}, which JAX-RS reports as {@code null} for
     * the {@code eddi://} scheme on an in-process call — read the wrong header and
     * every imported snippet silently survives a failed import.
     * <p>
     * <strong>Premise.</strong> The failure is injected at the agent write, so the
     * test depends on {@code unpackAndImportAgent} importing snippets
     * <em>before</em> it creates the agent. That is deliberate — snippets are
     * global and could plausibly be moved later — so the ordering is asserted
     * first, with its own message: if a reorder ever makes the snippet write
     * unreachable, this fails saying so rather than looking like a broken rollback.
     */
    @Test
    @DisplayName("a snippet created by a failed import is deleted again on rollback")
    void createdSnippetIsRolledBackWhenTheImportFails() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });
        when(restSnippetStore.readSnippetDescriptors("", 0, 0)).thenReturn(List.of());
        doReturn(created(CREATED_SNIPPET_ID)).when(restSnippetStore).createSnippet(any());

        var snippetStore = mock(IPromptSnippetStore.class);
        assertThrows(InternalServerErrorException.class, () -> runFailingImport(snippetStore));

        verify(restSnippetStore, description("premise: snippets must be imported before the failing agent write, "
                + "otherwise there is no created snippet for the rollback to undo")).createSnippet(any());
        verify(snippetStore).deleteAllPermanently(CREATED_SNIPPET_ID);
        verify(documentDescriptorStore).deleteAllDescriptor(CREATED_SNIPPET_ID);
    }

    /**
     * The mirror image: a merge matches an archived snippet to the local one of the
     * same name and updates it in place, rather than creating a second snippet with
     * the same natural key. And because that snippet already existed, it is not
     * this import's to delete — rolling it back would destroy a snippet other
     * agents share.
     */
    @Test
    @DisplayName("a merge updates the snippet of the same name instead of duplicating it")
    void updatedSnippetIsNotRolledBack() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create(
                "eddi://ai.labs.snippet/snippetstore/snippets/" + EXISTING_SNIPPET_ID + "?version=2"));
        when(restSnippetStore.readSnippetDescriptors("", 0, 0)).thenReturn(List.of(descriptor));
        var existing = new PromptSnippet();
        existing.setName("cautious_mode");
        when(restSnippetStore.readSnippet(EXISTING_SNIPPET_ID, 2)).thenReturn(existing);
        doReturn(Response.status(200).build()).when(restSnippetStore).updateSnippet(eq(EXISTING_SNIPPET_ID), eq(2), any());

        var snippetStore = mock(IPromptSnippetStore.class);
        assertThrows(InternalServerErrorException.class, () -> runFailingImport(snippetStore, true));

        verify(restSnippetStore).updateSnippet(eq(EXISTING_SNIPPET_ID), eq(2), any());
        verify(restSnippetStore, never()).createSnippet(any());
        verify(snippetStore, never()).deleteAllPermanently(anyString());
    }

    /**
     * The snippet surface is looked up from CDI <em>outside</em> the per-file guard
     * — a deployment where the prompt-snippet extension is not installed fails
     * there and never reaches it. That used to take the whole preview with it. It
     * costs the snippets and nothing else: the agent row is still there, and no
     * snippet row is invented for a store nobody could ask.
     */
    @Test
    @DisplayName("a snippet store CDI cannot resolve costs the snippet rows, not the preview")
    void unresolvableSnippetBeanCostsOnlyTheSnippetRows() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });

        ImportPreview preview;
        try (var cdi = stubCdiWithUnresolvableSnippetStore()) {
            preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);
        }

        assertEquals(AGENT_ORIGIN_ID, preview.sourceAgentId());
        assertTrue(preview.resources().stream().anyMatch(diff -> "agent".equals(diff.resourceType())),
                "the agent row must survive a snippet bean that cannot be resolved");
        assertTrue(preview.resources().stream().noneMatch(diff -> "snippet".equals(diff.resourceType())),
                "no snippet row can be built without the store, and a guessed one would be worse: "
                        + preview.resources());
    }

    /**
     * And the same on the import: the restore the operator asked for still lands.
     */
    @Test
    @DisplayName("a snippet store CDI cannot resolve does not fail the import")
    void unresolvableSnippetBeanDoesNotFailTheImport() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSnippet(dir, "good", "{\"name\":\"cautious_mode\",\"content\":\"be careful\"}");
        });

        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
        when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID)).thenReturn(resourceId(NEW_AGENT_ID, 1));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(NEW_AGENT_ID, 1)).thenReturn(descriptor);

        Response response;
        try (var cdi = stubCdiWithUnresolvableSnippetStore(IAgentStore.class, agentStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        assertEquals(201, response.getStatus(),
                "the agent is what the operator asked to restore; an unresolvable snippet bean must not take it down");
        verify(agentStore).create(any());
    }

    // ==================== Helpers ====================

    /** A 201 carrying the header {@code recordCreatedSnippet} reads the id from. */
    private static Response created(String snippetId) {
        return Response.status(201)
                .header("X-Resource-URI",
                        "eddi://ai.labs.snippet/snippetstore/snippets/" + snippetId + "?version=1")
                .build();
    }

    private void runFailingImport(IPromptSnippetStore snippetStore) throws Exception {
        runFailingImport(snippetStore, false);
    }

    /**
     * Imports the archive with an agent store that refuses to write, which is what
     * drives the rollback.
     */
    private void runFailingImport(IPromptSnippetStore snippetStore, boolean merge) throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenThrow(new IllegalStateException("agent store is down"));

        try (var cdi = stubCdi(IAgentStore.class, agentStore,
                IRestPromptSnippetStore.class, restSnippetStore,
                IPromptSnippetStore.class, snippetStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]),
                    merge ? "merge" : "create", null, null, null);
        }
    }

    private ImportPreview previewImport() throws Exception {
        try (var cdi = stubCdi(IRestPromptSnippetStore.class, restSnippetStore)) {
            return importService.previewImport(new ByteArrayInputStream(new byte[0]), null);
        }
    }

    private Response runImport() throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
        when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID)).thenReturn(resourceId(NEW_AGENT_ID, 1));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(NEW_AGENT_ID, 1)).thenReturn(descriptor);

        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestPromptSnippetStore.class, restSnippetStore)) {
            return importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }
    }

    private static void writeAgent(File dir) throws IOException {
        Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
        Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".descriptor.json").toPath(), "{\"name\":\"Agent\"}");
    }

    private static void writeSnippet(File dir, String fileName, String json) throws IOException {
        File snippets = new File(dir, "snippets");
        assertTrue(snippets.mkdirs() || snippets.isDirectory());
        Files.writeString(new File(snippets, fileName + ".snippet.json").toPath(), json);
    }

    private interface ArchiveContent {
        void write(File dir) throws IOException;
    }

    private void stubUnzip(ArchiveContent content) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            assertTrue(dir.mkdirs() || dir.isDirectory());
            content.write(dir);
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
    }

    /**
     * A CDI container that resolves everything asked of it except the snippet
     * surface — the failure mode of a deployment where the prompt-snippet extension
     * is not installed at all.
     */
    /**
     * The pairwise helpers below read {@code classThenStore[i + 1]}, so an odd
     * argument count used to walk off the end of the varargs array and fail with a
     * bare {@code ArrayIndexOutOfBoundsException} naming neither the helper nor the
     * missing store. The guard also has to run BEFORE
     * {@code mockStatic(CDI.class)}, or a rejected call leaks a static mock into
     * the next test in the class.
     */
    @Test
    @DisplayName("stubCdi rejects an odd argument count instead of reading past the array")
    void stubCdiRejectsAnUnpairedArgument() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> stubCdi(IAgentStore.class));
        assertTrue(thrown.getMessage().contains("PAIRS"),
                "the message must say what is wrong, not just that something is: " + thrown.getMessage());
        assertDoesNotThrow(() -> {
            try (var ignored = stubCdi()) {
                // An empty (even) argument list is legitimate and must still work; if the
                // guard had leaked a static CDI mock on the rejected call above, opening
                // this second one would fail.
            }
        }, "the rejected call must not have left a static CDI mock registered");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AutoCloseable stubCdiWithUnresolvableSnippetStore(Object... classThenStore) {
        if (classThenStore.length % 2 != 0) {
            throw new IllegalArgumentException("stubCdi takes (interface, store) PAIRS; got "
                    + classThenStore.length + " argument(s). An odd count means a store was left off, and the "
                    + "loop below would read past the end of the array instead of saying so.");
        }
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        for (int i = 0; i < classThenStore.length; i += 2) {
            select(cdi, (Class<Object>) classThenStore[i], classThenStore[i + 1]);
        }
        when(cdi.select(IRestPromptSnippetStore.class))
                .thenThrow(new UnsatisfiedResolutionException("no bean for IRestPromptSnippetStore"));
        return cdiMock;
    }

    @SuppressWarnings("unchecked")
    private AutoCloseable stubCdi(Object... classThenStore) {
        if (classThenStore.length % 2 != 0) {
            throw new IllegalArgumentException("stubCdi takes (interface, store) PAIRS; got "
                    + classThenStore.length + " argument(s). An odd count means a store was left off, and the "
                    + "loop below would read past the end of the array instead of saying so.");
        }
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        for (int i = 0; i < classThenStore.length; i += 2) {
            select(cdi, (Class<Object>) classThenStore[i], classThenStore[i + 1]);
        }
        return cdiMock;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void select(CDI cdi, Class<T> storeClass, T store) {
        var instance = (Instance<T>) mock(Instance.class);
        when(cdi.select(storeClass)).thenReturn(instance);
        when(instance.get()).thenReturn(store);
    }

    private static IResourceId resourceId(String id, int version) {
        return new IResourceId() {
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
}
