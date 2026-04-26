/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class ResourceUtilitiesTest {

    @Test
    void validateUri_validEddiUri_returnsResourceId() {
        var resourceId = ResourceUtilities.validateUri(
                "eddi://ai.labs.agent/agentstore/agents/5262b802dc6c4008b54c?version=1");
        assertNotNull(resourceId);
        assertEquals("5262b802dc6c4008b54c", resourceId.getId());
        assertEquals(1, resourceId.getVersion());
    }

    @Test
    void validateUri_nonEddiScheme_returnsNull() {
        assertNull(ResourceUtilities.validateUri("http://example.com/resource/123?version=1"));
    }

    @Test
    void validateUri_missingVersion_returnsNull() {
        assertNull(ResourceUtilities.validateUri("eddi://ai.labs.agent/agentstore/agents/abc123"));
    }

    @Test
    void createDocumentDescriptor_setsResourceAndDates() {
        URI resource = URI.create("eddi://ai.labs.agent/agentstore/agents/abc123?version=1");

        DocumentDescriptor descriptor = ResourceUtilities.createDocumentDescriptor(resource);

        assertEquals(resource, descriptor.getResource());
        assertEquals("", descriptor.getName());
        assertEquals("", descriptor.getDescription());
        assertNotNull(descriptor.getCreatedOn());
        assertNotNull(descriptor.getLastModifiedOn());
    }

    @Test
    void createDocumentDescriptor_datesMatch() {
        URI resource = URI.create("eddi://ai.labs.agent/agentstore/agents/abc123?version=1");
        DocumentDescriptor descriptor = ResourceUtilities.createDocumentDescriptor(resource);

        // Created and last modified should be the same on creation
        assertEquals(descriptor.getCreatedOn(), descriptor.getLastModifiedOn());
    }

    @Test
    void createConversationDescriptor_setsAllFields() {
        URI resource = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/conv1?version=1");
        URI agentResource = URI.create("eddi://ai.labs.agent/agentstore/agents/agent1?version=1");

        ConversationDescriptor descriptor = ResourceUtilities.createConversationDescriptorDocument(
                resource, agentResource, "user-1");

        assertEquals(resource, descriptor.getResource());
        assertEquals(agentResource, descriptor.getAgentResource());
        assertEquals("user-1", descriptor.getUserId());
        assertEquals(ConversationDescriptor.ViewState.UNSEEN, descriptor.getViewState());
        assertNotNull(descriptor.getCreatedOn());
        assertNull(descriptor.getCreatedBy());
    }
}
