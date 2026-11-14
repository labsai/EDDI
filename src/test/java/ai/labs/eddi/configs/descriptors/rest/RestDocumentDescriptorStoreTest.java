/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.rest;

import ai.labs.eddi.configs.descriptors.ConfigResourceTypes;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.SimpleDocumentDescriptor;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestDocumentDescriptorStoreTest {

    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;

    private RestDocumentDescriptorStore restStore;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        restStore = new RestDocumentDescriptorStore(documentDescriptorStore, listingGuard(),
                ConfigResourceTypes.of("agent", "ai.labs.agent", "ai.labs.workflow"));
    }

    @Test
    @DisplayName("readDescriptors — returns list from store")
    void readDescriptors() throws Exception {
        var desc = new DocumentDescriptor();
        desc.setName("test");
        when(documentDescriptorStore.readDescriptors(eq("agent"), eq("filter"), eq(0), eq(10), eq(false), any()))
                .thenReturn(List.of(desc));

        List<DocumentDescriptor> result = restStore.readDescriptors("agent", "filter", 0, 10, "");
        assertEquals(1, result.size());
        assertEquals("test", result.get(0).getName());
    }

    @Test
    @DisplayName("readDescriptors — throws InternalServerErrorException on ResourceStoreException")
    void readDescriptorsStoreException() throws Exception {
        when(documentDescriptorStore.readDescriptors(any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new IResourceStore.ResourceStoreException("db error"));

        assertThrows(InternalServerErrorException.class,
                () -> restStore.readDescriptors("agent", null, 0, 10, ""));
    }

    @Test
    @DisplayName("readDescriptors — throws NotFoundException on ResourceNotFoundException")
    void readDescriptorsNotFound() throws Exception {
        when(documentDescriptorStore.readDescriptors(any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

        assertThrows(NotFoundException.class,
                () -> restStore.readDescriptors("agent", null, 0, 10, ""));
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
    @DisplayName("patchDescriptor — a version that is not current is a 409, not a silent no-op")
    void patchDescriptorOfAStaleVersionIsAConflict() throws Exception {
        when(documentDescriptorStore.getCurrentResourceId("id1")).thenReturn(resourceId("id1", 3));
        var current = new DocumentDescriptor();
        current.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/id1?version=3"));
        when(documentDescriptorStore.readDescriptor("id1", 3)).thenReturn(current);

        var thrown = assertThrows(WebApplicationException.class, () -> restStore.patchDescriptor("id1", 1, rename("stale")));

        assertEquals(409, thrown.getResponse().getStatus());
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("patchDescriptor — the live resource version names the current descriptor even when the two drifted")
    void patchDescriptorByResourceVersionWritesTheCurrentDescriptor() throws Exception {
        // A merge import bumps the resource before its descriptor: resource v2,
        // descriptor v1.
        when(documentDescriptorStore.getCurrentResourceId("id1")).thenReturn(resourceId("id1", 1));
        var current = new DocumentDescriptor();
        current.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/id1?version=2"));
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(current);

        restStore.patchDescriptor("id1", 2, rename("renamed"));

        verify(documentDescriptorStore).setDescriptor("id1", 1, current);
        assertEquals("renamed", current.getName());
    }

    @Test
    @DisplayName("patchDescriptor — a PUT that moves the descriptor on during the write is a 409, not a 204 into history")
    void patchRacingAPutIsAConflict() throws Exception {
        var written = new AtomicBoolean();
        when(documentDescriptorStore.getCurrentResourceId("id1")).thenAnswer(i -> resourceId("id1", written.get() ? 2 : 1));
        var current = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(current);
        doAnswer(i -> {
            written.set(true);
            return null;
        }).when(documentDescriptorStore).setDescriptor(eq("id1"), eq(1), any());

        var thrown = assertThrows(WebApplicationException.class, () -> restStore.patchDescriptor("id1", 1, rename("late")));

        assertEquals(409, thrown.getResponse().getStatus());
    }

    private static PatchInstruction<DocumentDescriptor> rename(String name) {
        var patch = new DocumentDescriptor();
        patch.setName(name);
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setDocument(patch);
        instruction.setOperation(PatchInstruction.PatchOperation.SET);
        return instruction;
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
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

    // --- Security review 2026-11, PATCH side note: the endpoint applies name and
    // description only, and answered 204 to a document setting anything else — so
    // `visibility: "private"` was reported as done while nothing changed. ---

    /** The REST mapper's recipe, which ignores unknown properties. */
    private static final ObjectMapper REST_MAPPER = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

    private static PatchInstruction<DocumentDescriptor> setFromJson(String documentJson) throws Exception {
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setOperation(PatchInstruction.PatchOperation.SET);
        instruction.setDocument(REST_MAPPER.readValue(documentJson, DocumentDescriptor.class));
        return instruction;
    }

    @Test
    @DisplayName("patchDescriptor — a document setting visibility is a 400 naming it and the endpoint that changes it, and nothing is written")
    void patchSettingVisibilityIsRefused() throws Exception {
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(new DocumentDescriptor());

        var refused = assertThrows(BadRequestException.class,
                () -> restStore.patchDescriptor("id1", 1, setFromJson("{\"name\":\"n\",\"visibility\":\"private\"}")));

        assertTrue(refused.getMessage().contains("visibility"), refused.getMessage());
        assertTrue(refused.getMessage().contains("PUT /descriptorstore/descriptors/{id}/shares/visibility"), refused.getMessage());
        assertTrue(refused.getMessage().contains("nothing was changed"), refused.getMessage());
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("patchDescriptor — each sharing field points at its own endpoint, and EDDI-maintained fields are named as such")
    void patchSettingOtherDeclaredFieldsIsRefused() throws Exception {
        var refused = assertThrows(BadRequestException.class, () -> restStore.patchDescriptor("id1", 1, setFromJson(
                "{\"spaceId\":\"team:x\",\"ownerId\":\"bob\",\"grants\":[],\"deleted\":true,\"resource\":\"eddi://ai.labs.agent/x\",\"createdOn\":0}")));

        String message = refused.getMessage();
        for (String field : List.of("spaceId", "ownerId", "grants", "deleted", "resource", "createdOn")) {
            assertTrue(message.contains(field), field + " must be named: " + message);
        }
        assertTrue(message.contains("/shares/space?spaceId="), message);
        assertTrue(message.contains("/shares/owner?ownerId="), message);
        assertTrue(message.contains("maintained by EDDI"), message);
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("patchDescriptor — a key the descriptor does not declare (resourceVisibility) is refused too, quoted and sanitised")
    void patchSettingAnUnmodeledFieldIsRefused() throws Exception {
        var refused = assertThrows(BadRequestException.class,
                () -> restStore.patchDescriptor("id1", 1, setFromJson("{\"name\":\"n\",\"resourceVisibility\":\"private\",\"evil\\nkey\":1}")));

        String message = refused.getMessage();
        assertTrue(message.contains("'resourceVisibility'"), message);
        assertTrue(message.contains("'evil_key'"), "a caller's key is echoed only sanitised: " + message);
        assertFalse(message.contains("\n"), message);
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("patchDescriptor — a body with many unknown keys names ten and counts the rest")
    void patchEchoIsBounded() {
        var json = new StringBuilder("{");
        for (int i = 0; i < 25; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":1");
        }
        json.append("}");

        var refused = assertThrows(BadRequestException.class, () -> restStore.patchDescriptor("id1", 1, setFromJson(json.toString())));

        assertTrue(refused.getMessage().contains("'k9'"), refused.getMessage());
        assertFalse(refused.getMessage().contains("'k10'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("15 more"), refused.getMessage());
    }

    @Test
    @DisplayName("patchDescriptor — a DELETE naming another field is refused as well: it would not clear it either")
    void deletePatchNamingAnotherFieldIsRefused() throws Exception {
        var instruction = new PatchInstruction<DocumentDescriptor>();
        instruction.setOperation(PatchInstruction.PatchOperation.DELETE);
        instruction.setDocument(REST_MAPPER.readValue("{\"grants\":[]}", DocumentDescriptor.class));

        assertThrows(BadRequestException.class, () -> restStore.patchDescriptor("id1", 1, instruction));
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("patchDescriptor — what every caller sends still goes through: name and description, deleted:false, read-only fields")
    void patchOfNameAndDescriptionStillApplies() throws Exception {
        var existing = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor("id1", 1)).thenReturn(existing);

        // deleted:false is the primitive's default and so indistinguishable from
        // absent; callerLevel and ownerName are read-only and never deserialised.
        restStore.patchDescriptor("id1", 1,
                setFromJson("{\"name\":\"n\",\"description\":\"d\",\"deleted\":false,\"callerLevel\":\"OWN\",\"ownerName\":\"Alice\"}"));

        verify(documentDescriptorStore).setDescriptor("id1", 1, existing);
        assertEquals("n", existing.getName());
        assertEquals("d", existing.getDescription());
    }

    @Test
    @DisplayName("the keys a descriptor did not declare are never serialised")
    void unmodeledFieldsAreNotSerialised() throws Exception {
        var descriptor = REST_MAPPER.readValue("{\"name\":\"n\",\"resourceVisibility\":\"private\"}", DocumentDescriptor.class);

        assertEquals(Set.of("resourceVisibility"), descriptor.unmodeledFields());
        String written = REST_MAPPER.writeValueAsString(descriptor);
        assertFalse(written.contains("resourceVisibility"), written);
        assertFalse(written.contains("unmodeled"), written);
        assertTrue(new DocumentDescriptor().unmodeledFields().isEmpty());
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

    // --- F1: the generic descriptor API answers for configuration only. A
    // conversation descriptor shares the collection; renaming one through this API
    // wrote it back without its owner, after which every caller could read it. ---

    private static final String CONVERSATION_ID = "0a0a0a0a0a0a0a0a0a0a0a0c";

    private void conversationDescriptorStored() throws Exception {
        var conversation = new DocumentDescriptor();
        conversation.setResource(URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + CONVERSATION_ID + "?version=0"));
        lenient().when(documentDescriptorStore.readCurrentDescriptor(CONVERSATION_ID)).thenReturn(conversation);
        lenient().when(documentDescriptorStore.readDescriptor(eq(CONVERSATION_ID), anyInt())).thenReturn(conversation);
        lenient().when(documentDescriptorStore.getCurrentResourceId(CONVERSATION_ID)).thenReturn(resourceId(CONVERSATION_ID, 0));
    }

    @Test
    @DisplayName("F1: listing conversation descriptors through the generic API is refused")
    void listingConversationsIsRefused() throws Exception {
        assertThrows(BadRequestException.class, () -> restStore.readDescriptors("ai.labs.conversation", "user-c", 0, 20, ""));
        // A blank type is a prefix of every descriptor, conversations included.
        assertThrows(BadRequestException.class, () -> restStore.readDescriptors("", "", 0, 20, ""));
        // So is a truncated type.
        assertThrows(BadRequestException.class, () -> restStore.readDescriptors("ai.labs.", "", 0, 20, ""));
        verify(documentDescriptorStore, never()).readDescriptors(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("F1: patching a conversation descriptor is refused as not found, and nothing is written")
    void patchingAConversationDescriptorIsRefused() throws Exception {
        conversationDescriptorStored();

        assertThrows(NotFoundException.class, () -> restStore.patchDescriptor(CONVERSATION_ID, 0, rename("x")));
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("F1: reading a conversation descriptor through the generic API is refused as not found")
    void readingAConversationDescriptorIsRefused() throws Exception {
        conversationDescriptorStored();

        assertThrows(NotFoundException.class, () -> restStore.readDescriptor(CONVERSATION_ID, 0));
        assertThrows(NotFoundException.class, () -> restStore.readSimpleDescriptor(CONVERSATION_ID, 0));
        verify(documentDescriptorStore, never()).readDescriptor(anyString(), anyInt());
    }

    @Test
    @DisplayName("a configuration descriptor is still patched, name only — nothing else it carries changes")
    void patchingAConfigurationDescriptorStillWorks() throws Exception {
        var agent = new DocumentDescriptor();
        agent.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/0a0a0a0a0a0a0a0a0a0a0a0a?version=1"));
        agent.setOwnerId("alice");
        agent.setDescription("keep me");
        when(documentDescriptorStore.readCurrentDescriptor("0a0a0a0a0a0a0a0a0a0a0a0a")).thenReturn(agent);
        when(documentDescriptorStore.readDescriptor("0a0a0a0a0a0a0a0a0a0a0a0a", 1)).thenReturn(agent);

        restStore.patchDescriptor("0a0a0a0a0a0a0a0a0a0a0a0a", 1, rename("renamed"));

        verify(documentDescriptorStore).setDescriptor("0a0a0a0a0a0a0a0a0a0a0a0a", 1, agent);
        assertEquals("renamed", agent.getName());
        assertEquals("keep me", agent.getDescription());
        assertEquals("alice", agent.getOwnerId());
    }

    /**
     * A guard that sees everything. {@code listingScope()} is chained onto
     * ({@code .withinSpace(...)}), and production never returns null there — only a
     * bare mock does, so the stub keeps the double honest rather than making the
     * call site defend against its own bean.
     */
    private static ResourceAccessGuard listingGuard() {
        ResourceAccessGuard guard = mock(ResourceAccessGuard.class);
        lenient().when(guard.seesEverything()).thenReturn(true);
        lenient().when(guard.listingScope()).thenReturn(AccessScope.unrestricted());
        return guard;
    }
}
