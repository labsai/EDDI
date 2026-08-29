/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.SimpleDocumentDescriptor;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestDocumentDescriptorStoreTest {

    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;

    private RestDocumentDescriptorStore restStore;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        restStore = new RestDocumentDescriptorStore(documentDescriptorStore, mock(ResourceAccessGuard.class));
    }

    @Test
    @DisplayName("readDescriptors — returns list from store")
    void readDescriptors() throws Exception {
        var desc = new DocumentDescriptor();
        desc.setName("test");
        when(documentDescriptorStore.readDescriptors(eq("agent"), eq("filter"), eq(0), eq(10), eq(false), any()))
                .thenReturn(List.of(desc));

        List<DocumentDescriptor> result = restStore.readDescriptors("agent", "filter", 0, 10);
        assertEquals(1, result.size());
        assertEquals("test", result.get(0).getName());
    }

    @Test
    @DisplayName("readDescriptors — throws InternalServerErrorException on ResourceStoreException")
    void readDescriptorsStoreException() throws Exception {
        when(documentDescriptorStore.readDescriptors(any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new IResourceStore.ResourceStoreException("db error"));

        assertThrows(InternalServerErrorException.class,
                () -> restStore.readDescriptors("agent", null, 0, 10));
    }

    @Test
    @DisplayName("readDescriptors — throws NotFoundException on ResourceNotFoundException")
    void readDescriptorsNotFound() throws Exception {
        when(documentDescriptorStore.readDescriptors(any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

        assertThrows(NotFoundException.class,
                () -> restStore.readDescriptors("agent", null, 0, 10));
    }

    @Test
    @DisplayName("readDescriptor — returns descriptor")
    void readDescriptor() throws Exception {
        var desc = new DocumentDescriptor();
        desc.setName("myDesc");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(desc);

        DocumentDescriptor result = restStore.readDescriptor("id1", 1);
        assertEquals("myDesc", result.getName());
    }

    @Test
    @DisplayName("readDescriptor — throws InternalServerErrorException on store error")
    void readDescriptorStoreError() throws Exception {
        when(documentDescriptorStore.readDescriptor(any(), anyInt()))
                .thenThrow(new IResourceStore.ResourceStoreException("error"));

        assertThrows(InternalServerErrorException.class,
                () -> restStore.readDescriptor("id1", 1));
    }

    @Test
    @DisplayName("readDescriptor — throws NotFoundException when not found")
    void readDescriptorNotFound() throws Exception {
        when(documentDescriptorStore.readDescriptor(any(), anyInt()))
                .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

        assertThrows(NotFoundException.class,
                () -> restStore.readDescriptor("id1", 1));
    }

    @Test
    @DisplayName("readSimpleDescriptor — returns simple descriptor")
    void readSimpleDescriptor() throws Exception {
        var desc = new DocumentDescriptor();
        desc.setName("Agent-1");
        desc.setDescription("My agent");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(desc);

        SimpleDocumentDescriptor result = restStore.readSimpleDescriptor("id1", 1);
        assertEquals("Agent-1", result.getName());
        assertEquals("My agent", result.getDescription());
    }

    @Test
    @DisplayName("patchDescriptor — SET operation updates name and description")
    void patchDescriptorSet() throws Exception {
        var existing = new DocumentDescriptor();
        existing.setName("old");
        existing.setDescription("old desc");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        var patch = new DocumentDescriptor();
        patch.setName("new name");
        patch.setDescription("new desc");
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(patch);
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        restStore.patchDescriptor("id1", 1, instruction);

        verify(documentDescriptorStore).setDescriptor("id1", 1, existing);
        assertEquals("new name", existing.getName());
        assertEquals("new desc", existing.getDescription());
    }

    @Test
    @DisplayName("patchDescriptor — DELETE operation clears name and description")
    void patchDescriptorDelete() throws Exception {
        var existing = new DocumentDescriptor();
        existing.setName("old");
        existing.setDescription("old desc");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(new DocumentDescriptor());
        instruction.setOperation(PatchInstruction.PatchOperation.DELETE);

        restStore.patchDescriptor("id1", 1, instruction);

        verify(documentDescriptorStore).setDescriptor("id1", 1, existing);
        assertEquals("", existing.getName());
        assertEquals("", existing.getDescription());
    }

    /**
     * The endpoint calls itself "Partial update" and used to assign both fields
     * unconditionally, so a caller sending only {@code description} — exactly what
     * the documentation describes — wiped the descriptor's {@code name} and got a
     * 204 for it. The agent then rendered as unnamed in every listing, and
     * recovering it meant knowing to re-send both fields.
     */
    @Test
    @DisplayName("patchDescriptor — SET with only a description leaves the name alone")
    void patchDescriptorSetMergesRatherThanReplaces() throws Exception {
        var existing = new DocumentDescriptor();
        existing.setName("Support Agent");
        existing.setDescription("old desc");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        var patch = new DocumentDescriptor();
        patch.setDescription("new desc");
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(patch);
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        restStore.patchDescriptor("id1", 1, instruction);

        assertEquals("Support Agent", existing.getName(), "a partial update must not destroy the fields it omits");
        assertEquals("new desc", existing.getDescription());
    }

    @Test
    @DisplayName("patchDescriptor — SET with only a name leaves the description alone")
    void patchDescriptorSetNameOnly() throws Exception {
        var existing = new DocumentDescriptor();
        existing.setName("old");
        existing.setDescription("Handles refunds");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        var patch = new DocumentDescriptor();
        patch.setName("Refund Agent");
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(patch);
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        restStore.patchDescriptor("id1", 1, instruction);

        assertEquals("Refund Agent", existing.getName());
        assertEquals("Handles refunds", existing.getDescription());
    }

    @Test
    @DisplayName("patchDescriptor — DELETE naming one field clears only that field")
    void patchDescriptorDeleteIsFieldSelective() throws Exception {
        var existing = new DocumentDescriptor();
        existing.setName("Support Agent");
        existing.setDescription("old desc");
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        var patch = new DocumentDescriptor();
        patch.setDescription("old desc");
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(patch);
        instruction.setOperation(PatchInstruction.PatchOperation.DELETE);

        restStore.patchDescriptor("id1", 1, instruction);

        assertEquals("Support Agent", existing.getName());
        assertEquals("", existing.getDescription());
    }

    /**
     * A body that is not the wrapper deserialises to an instruction with a null
     * operation, which used to be dereferenced — so a client-side shape error came
     * back as a 500 error page and an NPE in the container log.
     */
    @Test
    @DisplayName("patchDescriptor — a body that is not a PatchInstruction is a 400, not a 500")
    void patchDescriptorRejectsMalformedBody() {
        var noOperation = new PatchInstruction<DocumentDescriptor>();
        noOperation.setDocument(new DocumentDescriptor());

        var badRequest = assertThrows(BadRequestException.class,
                () -> restStore.patchDescriptor("id1", 1, noOperation));
        assertTrue(badRequest.getMessage().contains("PatchInstruction"),
                "the message must name the shape the caller should have sent");

        assertThrows(BadRequestException.class, () -> restStore.patchDescriptor("id1", 1, null));
    }

    @Test
    @DisplayName("patchDescriptor — SET without a document is a 400")
    void patchDescriptorSetWithoutDocument() throws Exception {
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(new DocumentDescriptor());

        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        assertThrows(BadRequestException.class, () -> restStore.patchDescriptor("id1", 1, instruction));
    }

    @Test
    @DisplayName("patchDescriptor — throws InternalServerErrorException on store error")
    void patchDescriptorStoreError() throws Exception {
        when(documentDescriptorStore.readDescriptor(any(), anyInt()))
                .thenThrow(new IResourceStore.ResourceStoreException("error"));

        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(new DocumentDescriptor());
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        assertThrows(InternalServerErrorException.class,
                () -> restStore.patchDescriptor("id1", 1, instruction));
    }

    @Test
    @DisplayName("patchDescriptor — throws NotFoundException when descriptor not found")
    void patchDescriptorNotFound() throws Exception {
        when(documentDescriptorStore.readDescriptor(any(), anyInt()))
                .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(new DocumentDescriptor());
        instruction.setOperation(PatchInstruction.PatchOperation.SET);

        assertThrows(NotFoundException.class,
                () -> restStore.patchDescriptor("id1", 1, instruction));
    }
}
