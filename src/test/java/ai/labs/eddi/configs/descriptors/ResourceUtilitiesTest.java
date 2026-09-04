/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

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

    // ==================== filterAndPage ====================

    /**
     * The reverse-reference listings post-filter with this helper because the store
     * lookup behind them takes neither a filter nor a page. Its Javadoc claims it
     * approximates "the same descriptor fields the descriptor store matches on", so
     * which fields it actually matches is the contract — a narrower set silently
     * disagrees with the store-side filter for the same query string.
     */
    @Nested
    @DisplayName("filterAndPage")
    class FilterAndPage {

        private DocumentDescriptor descriptor(String name, String description, String ownerId, String resource) {
            var descriptor = new DocumentDescriptor();
            descriptor.setName(name);
            descriptor.setDescription(description);
            descriptor.setOwnerId(ownerId);
            if (resource != null) {
                descriptor.setResource(URI.create(resource));
            }
            return descriptor;
        }

        private List<String> names(List<DocumentDescriptor> descriptors) {
            return descriptors.stream().map(DocumentDescriptor::getName).toList();
        }

        private List<DocumentDescriptor> fixture() {
            return List.of(
                    descriptor("alpha", "the first one", "owner-a", "eddi://ai.labs.agent/agentstore/agents/111?version=1"),
                    descriptor("beta", "second, tagged", "owner-b", "eddi://ai.labs.agent/agentstore/agents/222?version=1"),
                    descriptor("gamma", "third, tagged", "owner-c", "eddi://ai.labs.agent/agentstore/agents/333?version=1"));
        }

        @Test
        @DisplayName("matches the description, not only the name")
        void matchesDescription() {
            assertEquals(List.of("alpha"), names(ResourceUtilities.filterAndPage(fixture(), "FIRST", 0, 20)));
        }

        @Test
        @DisplayName("matches the owner id")
        void matchesOwnerId() {
            assertEquals(List.of("beta"), names(ResourceUtilities.filterAndPage(fixture(), "owner-b", 0, 20)));
        }

        @Test
        @DisplayName("matches the resource uri")
        void matchesResourceUri() {
            assertEquals(List.of("gamma"), names(ResourceUtilities.filterAndPage(fixture(), "agents/333", 0, 20)));
        }

        @Test
        @DisplayName("a descriptor with null fields is skipped, not an NPE")
        void tolerantOfNullFields() {
            var incomplete = List.of(descriptor(null, null, null, null));

            assertTrue(ResourceUtilities.filterAndPage(incomplete, "anything", 0, 20).isEmpty());
        }

        /**
         * {@code limit} arrives from the REST layer, where the POST overloads declare
         * {@code @DefaultValue("20")} but an in-process caller may pass null or 0. Both
         * mean "no page", i.e. return everything — truncating instead would silently
         * drop rows.
         */
        @Test
        @DisplayName("a null or non-positive limit returns everything")
        void noLimitReturnsEverything() {
            assertEquals(List.of("alpha", "beta", "gamma"), names(ResourceUtilities.filterAndPage(fixture(), null, 0, null)));
            assertEquals(List.of("alpha", "beta", "gamma"), names(ResourceUtilities.filterAndPage(fixture(), null, 0, 0)));
            assertEquals(List.of("alpha", "beta", "gamma"), names(ResourceUtilities.filterAndPage(fixture(), null, 0, -1)));
        }

        @Test
        @DisplayName("index is a page number, and a negative one is the first page")
        void indexIsAPageNumber() {
            assertEquals(List.of("alpha", "beta"), names(ResourceUtilities.filterAndPage(fixture(), null, 0, 2)));
            assertEquals(List.of("gamma"), names(ResourceUtilities.filterAndPage(fixture(), null, 1, 2)));
            assertEquals(List.of("alpha", "beta"), names(ResourceUtilities.filterAndPage(fixture(), null, -3, 2)));
            assertEquals(List.of("alpha", "beta"), names(ResourceUtilities.filterAndPage(fixture(), null, null, 2)));
            assertTrue(ResourceUtilities.filterAndPage(fixture(), null, 9, 2).isEmpty(), "past the end must be empty, not the full list");
        }

        @Test
        @DisplayName("filter and page compose: the page is taken from the matches, not the input")
        void filterThenPage() {
            assertEquals(List.of("beta"), names(ResourceUtilities.filterAndPage(fixture(), "tagged", 0, 1)));
            assertEquals(List.of("gamma"), names(ResourceUtilities.filterAndPage(fixture(), "tagged", 1, 1)));
            assertTrue(ResourceUtilities.filterAndPage(fixture(), "tagged", 2, 1).isEmpty());
        }

        @Test
        @DisplayName("null and empty inputs are handed straight back")
        void nullAndEmptyInputs() {
            assertNull(ResourceUtilities.filterAndPage(null, "x", 0, 20));
            assertTrue(ResourceUtilities.filterAndPage(List.of(), "x", 0, 20).isEmpty());
        }
    }
}
