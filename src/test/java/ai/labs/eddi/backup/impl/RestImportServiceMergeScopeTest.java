/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import com.fasterxml.jackson.core.JsonParseException;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Merge import resolves "the local copy of this archived resource" by the
 * archive's origin id — deployment-wide. With workspaces enforced that matched
 * another workspace's copy of the same archive and wrote into it. These pin
 * that a merge only ever resolves a resource the importer may edit, in the
 * space it is importing into.
 */
@DisplayName("RestImportService — merge target scoping")
class RestImportServiceMergeScopeTest {

    private static final String ORIGIN = "6650f0c2a1b2c3d4e5f60718";

    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard guard;
    private SpaceContext spaceContext;
    private WorkspaceSettings settings;
    private RestImportService service;

    @BeforeEach
    void setUp() {
        descriptorStore = mock(IDocumentDescriptorStore.class);
        guard = mock(ResourceAccessGuard.class);
        spaceContext = mock(SpaceContext.class);
        settings = mock(WorkspaceSettings.class);
        when(guard.settings()).thenReturn(settings);
        service = new RestImportService(null, null, null, descriptorStore, null, null, null, null, null, guard, spaceContext, null, true,
                false, Optional.empty());
    }

    private static DocumentDescriptor descriptor(String id, String space) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + id + "?version=1"));
        descriptor.setOriginId(ORIGIN);
        descriptor.setSpaceId(space);
        return descriptor;
    }

    private URI findLocal(String originId) throws Exception {
        Method method = RestImportService.class.getDeclaredMethod("findLocalUriByOriginId", String.class);
        method.setAccessible(true);
        return (URI) method.invoke(service, originId);
    }

    @Test
    @DisplayName("workspaces enforced: another workspace's copy is skipped, the importer's own is the target")
    void resolvesOnlyWithinTheImportersSpace() throws Exception {
        when(settings.isEnforcing()).thenReturn(true);
        when(spaceContext.defaultWriteSpace()).thenReturn("team:a");
        var otherTeams = descriptor("aaaaaaaaaaaaaaaaaaaaaaab", "team:b");
        var ours = descriptor("aaaaaaaaaaaaaaaaaaaaaaaa", "team:a");
        // listed newest first, and the other team's copy is newer
        when(descriptorStore.findByOriginId(ORIGIN)).thenReturn(List.of(otherTeams, ours));
        when(guard.canAccess(any(), eq(AccessLevel.EDIT))).thenReturn(true);

        assertEquals(ours.getResource(), findLocal(ORIGIN));
    }

    @Test
    @DisplayName("workspaces enforced: a copy the importer cannot edit is never the target")
    void skipsWhatTheImporterCannotEdit() throws Exception {
        when(settings.isEnforcing()).thenReturn(true);
        when(spaceContext.defaultWriteSpace()).thenReturn(null);
        var foreign = descriptor("aaaaaaaaaaaaaaaaaaaaaaab", "team:b");
        when(descriptorStore.findByOriginId(ORIGIN)).thenReturn(List.of(foreign));
        when(guard.canAccess(foreign, AccessLevel.EDIT)).thenReturn(false);

        assertNull(findLocal(ORIGIN), "no editable match means the merge creates a new copy");
    }

    @Test
    @DisplayName("workspaces off: the deployment-wide match is used, as before")
    void unchangedWithWorkspacesOff() throws Exception {
        when(settings.isEnforcing()).thenReturn(false);
        var only = descriptor("aaaaaaaaaaaaaaaaaaaaaaab", "team:b");
        when(descriptorStore.findByOriginId(ORIGIN)).thenReturn(List.of(only));

        assertEquals(only.getResource(), findLocal(ORIGIN));
    }

    @Test
    @DisplayName("an unstamped (pre-workspaces) resource is judged by the access check alone")
    void legacyUnstampedResource() {
        when(settings.isEnforcing()).thenReturn(true);
        when(spaceContext.defaultWriteSpace()).thenReturn("team:a");
        var legacy = descriptor("aaaaaaaaaaaaaaaaaaaaaaab", null);
        when(guard.canAccess(legacy, AccessLevel.EDIT)).thenReturn(true);

        assertTrue(service.isMergeTarget(legacy));
        assertFalse(service.isMergeTarget(null));
    }

    @Test
    @DisplayName("a zip-slip archive is refused with 400 and the reason, not 500")
    void zipSlipIsABadRequest() throws Exception {
        IZipArchive zip = mock(IZipArchive.class);
        doThrow(new ZipArchive.MalformedArchiveException("Zip entry escapes target directory")).when(zip).unzip(any(), any());
        var importer = new RestImportService(zip, null, null, descriptorStore, null, null, null, null, null, guard, spaceContext, null, true,
                false, Optional.empty());

        var refused = assertThrows(WebApplicationException.class,
                () -> importer.previewImport(new ByteArrayInputStream(new byte[]{1}), null));

        assertEquals(400, refused.getResponse().getStatus());
        assertTrue(String.valueOf(refused.getResponse().getEntity()).contains("escapes target directory"));
    }

    @Test
    @DisplayName("an upgrade preview the guard refuses on the target agent answers 403, not 500")
    void upgradePreviewWithoutAccessIsForbidden() throws Exception {
        StructuralMatcher matcher = mock(StructuralMatcher.class);
        when(matcher.buildPreview(any(), eq("target-agent"), eq(true)))
                .thenThrow(new ForbiddenException("Access denied: you do not have view access to this agent"));
        var importer = new RestImportService(mock(IZipArchive.class), null, null, descriptorStore, null, matcher, null, null, null, guard,
                spaceContext, null, true, false, Optional.empty());

        var refused = assertThrows(WebApplicationException.class,
                () -> importer.previewImport(new ByteArrayInputStream(new byte[]{1}), "target-agent"));

        assertEquals(403, refused.getResponse().getStatus());
        assertTrue(String.valueOf(refused.getResponse().getEntity()).contains("view access"));
    }

    @Test
    @DisplayName("a config file that is not JSON is a 400 naming the problem, anything else stays a 500")
    void invalidJsonInTheArchiveIsABadRequest() {
        WebApplicationException json = RestImportService.archiveReadFailure("",
                new IOException(new JsonParseException(null, "Unexpected character")));
        assertEquals(400, json.getResponse().getStatus());
        assertTrue(String.valueOf(json.getResponse().getEntity()).contains("not valid JSON"));

        WebApplicationException other = RestImportService.archiveReadFailure("Preview failed: ", new IOException("disk full"));
        assertEquals(500, other.getResponse().getStatus());
    }
}
