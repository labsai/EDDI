/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backfill is what stands between "enable workspaces" and "every
 * pre-existing resource disappears", so the two ways it can silently fail —
 * querying a descriptor type that does not exist, and recording itself complete
 * after a failure — are what these tests are about.
 */
class WorkspaceAccessIndexMigrationTest {

    @Test
    @DisplayName("the type list matches what listings actually query, not the ZIP file-extension names")
    void typesMatchTheStores() {
        List<String> types = WorkspaceAccessIndexMigration.DESCRIPTOR_TYPES;

        // The v6 names. Each of these has a v5 near-miss that reads plausibly and
        // matches nothing: behavior/rules, httpcalls/apicalls, langchain/llm,
        // regulardictionary/dictionary (AGENTS.md §5.5).
        assertTrue(types.contains("ai.labs.rules"), "rule sets are listed under ai.labs.rules");
        assertTrue(types.contains("ai.labs.apicalls"), "api calls are listed under ai.labs.apicalls");
        assertTrue(types.contains("ai.labs.llm"), "LLM configs are listed under ai.labs.llm");
        assertTrue(types.contains("ai.labs.dictionary"), "dictionaries are listed under ai.labs.dictionary");

        assertFalse(types.contains("ai.labs.behavior"), "ai.labs.behavior is the file extension, not the descriptor type");
        assertFalse(types.contains("ai.labs.httpcalls"), "ai.labs.httpcalls is the file extension, not the descriptor type");
        assertFalse(types.contains("ai.labs.langchain"), "ai.labs.langchain is the file extension, not the descriptor type");
        assertFalse(types.contains("ai.labs.regulardictionary"), "ai.labs.regulardictionary is not the descriptor type");

        assertEquals(15, types.size(), "one entry per configuration type with a REST store");
        assertEquals(types.size(), types.stream().distinct().count(), "no duplicates");
    }

    @Test
    @DisplayName("a page that could not be read leaves the migration to retry, rather than recording completion")
    void doesNotRecordCompletionAfterAFailure() throws Exception {
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var logStore = mock(IMigrationLogStore.class);
        when(logStore.readMigrationLog(anyString())).thenReturn(null);
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IResourceStore.ResourceStoreException("db down", null));

        new WorkspaceAccessIndexMigration(descriptorStore, logStore).runIfNeeded();

        // Recording completion here would strand every descriptor without an index —
        // invisible in every listing once enforcement is on, with no way to re-run.
        verify(logStore, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("a failed WRITE also blocks completion, not just a failed read")
    void doesNotRecordCompletionAfterAFailedWrite() throws Exception {
        // The read-failure case was covered; this one was not, and it is the more
        // likely of the two. `stampIfNeeded` swallowed the write exception and
        // returned "not written", which was indistinguishable from "already
        // correct" — so a run where every write threw recorded itself complete and
        // left those descriptors with no access index. Under enforcement that means
        // invisible in every listing, with no way to re-run short of deleting the
        // log row by hand.
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var logStore = mock(IMigrationLogStore.class);
        when(logStore.readMigrationLog(anyString())).thenReturn(null);

        DocumentDescriptor unstamped = new DocumentDescriptor();
        unstamped.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/abcdef1234567890abcdef?version=1"));
        unstamped.setOwnerId("alice");
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(unstamped))
                .thenReturn(List.of());
        doThrow(new IResourceStore.ResourceStoreException("write rejected", null))
                .when(descriptorStore).setDescriptor(anyString(), anyInt(), any());

        new WorkspaceAccessIndexMigration(descriptorStore, logStore).runIfNeeded();

        verify(logStore, never()).createMigrationLog(any());
    }

    @Test
    @DisplayName("a descriptor that cannot be addressed at all does not block completion forever")
    void unstampableDescriptorDoesNotBlockCompletion() throws Exception {
        // A descriptor with an unparseable resource URI would fail identically on
        // every retry, so holding the migration open for it would mean it never
        // completes — a different way to strand the deployment.
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var logStore = mock(IMigrationLogStore.class);
        when(logStore.readMigrationLog(anyString())).thenReturn(null);

        DocumentDescriptor unaddressable = new DocumentDescriptor();
        unaddressable.setOwnerId("alice");
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(unaddressable))
                .thenReturn(List.of());

        new WorkspaceAccessIndexMigration(descriptorStore, logStore).runIfNeeded();

        verify(logStore).createMigrationLog(any());
    }

    @Test
    @DisplayName("a clean sweep stamps the index and records completion")
    void stampsAndCompletes() throws Exception {
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var logStore = mock(IMigrationLogStore.class);
        when(logStore.readMigrationLog(anyString())).thenReturn(null);

        DocumentDescriptor legacy = new DocumentDescriptor();
        legacy.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/abcdef1234567890abcdef?version=1"));
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(legacy)).thenReturn(List.of());

        new WorkspaceAccessIndexMigration(descriptorStore, logStore).runIfNeeded();

        assertTrue(legacy.getAccessIndex() != null && legacy.getAccessIndex().contains("legacy"),
                "an unowned descriptor must carry the legacy token, or no predicate matches it at all");
        verify(logStore).createMigrationLog(any());
    }

    @Test
    @DisplayName("an already-applied migration does not sweep again")
    void skipsWhenAlreadyApplied() throws Exception {
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var logStore = mock(IMigrationLogStore.class);
        when(logStore.readMigrationLog(anyString())).thenReturn(new MigrationLog("x"));

        new WorkspaceAccessIndexMigration(descriptorStore, logStore).runIfNeeded();

        verify(descriptorStore, never()).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
    }
}
