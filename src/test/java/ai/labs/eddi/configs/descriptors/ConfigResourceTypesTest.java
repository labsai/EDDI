/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.triggermanagement.IRestUserConversationStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConfigResourceTypes — the descriptor types the generic API may address")
class ConfigResourceTypesTest {

    private static IRestVersionInfo store(String resourceUri) {
        IRestVersionInfo store = mock(IRestVersionInfo.class);
        when(store.getResourceURI()).thenReturn(resourceUri);
        return store;
    }

    @SuppressWarnings("unchecked")
    private static Instance<IRestVersionInfo> instanceOf(List<IRestVersionInfo> stores) {
        Instance<IRestVersionInfo> instance = mock(Instance.class);
        when(instance.stream()).thenAnswer(i -> new ArrayList<>(stores).stream());
        return instance;
    }

    private static DocumentDescriptor describing(String resource) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(resource == null ? null : URI.create(resource));
        return descriptor;
    }

    @Test
    @DisplayName("derived from the configuration stores' resource URIs")
    void derivedFromTheStores() {
        var types = new ConfigResourceTypes(instanceOf(List.of(store(IRestAgentStore.resourceURI), store(IRestWorkflowStore.resourceURI))));

        assertEquals(Set.of("ai.labs.agent", "ai.labs.workflow"), types.types());
        assertTrue(types.isConfigType("ai.labs.agent"));
        assertTrue(types.isConfigDescriptor(describing(IRestAgentStore.resourceURI + "0a0a0a0a0a0a0a0a0a0a0a0a?version=1")));
    }

    @Test
    @DisplayName("a conversation, or any other non-configuration descriptor, is not configuration")
    void nonConfigurationIsRefused() {
        var types = new ConfigResourceTypes(instanceOf(List.of(store(IRestAgentStore.resourceURI))));

        assertFalse(types.isConfigType("ai.labs.conversation"));
        assertFalse(types.isConfigDescriptor(describing(IConversationDescriptorStore.resourceUri + "0a0a0a0a0a0a0a0a0a0a0a0a?version=0")));
        assertFalse(types.isConfigDescriptor(describing(IRestUserConversationStore.resourceURI + "x")));
        // Prefixes are not types: the listing used to match the type as a URI prefix.
        assertFalse(types.isConfigType(""));
        assertFalse(types.isConfigType("ai.labs."));
        assertFalse(types.isConfigType(null));
        // No resource, or not an eddi:// one, is nothing the generic API handles.
        assertFalse(types.isConfigDescriptor(describing(null)));
        assertFalse(types.isConfigDescriptor(describing("https://ai.labs.agent/agentstore/agents/x")));
        assertFalse(types.isConfigDescriptor(null));
    }

    @Test
    @DisplayName("a store whose URI cannot be read does not break the others")
    void unreadableStoreIsSkipped() {
        IRestVersionInfo broken = mock(IRestVersionInfo.class);
        when(broken.getResourceURI()).thenThrow(new IllegalStateException("not ready"));

        var types = new ConfigResourceTypes(instanceOf(List.of(broken, store(IRestAgentStore.resourceURI))));

        assertEquals(Set.of("ai.labs.agent"), types.types());
    }

    @Test
    @DisplayName("a partial answer is not cached — the store that failed is asked again")
    void partialAnswerIsNotCached() {
        IRestVersionInfo flaky = mock(IRestVersionInfo.class);
        when(flaky.getResourceURI()).thenThrow(new IllegalStateException("not ready")).thenReturn(IRestWorkflowStore.resourceURI);

        var types = new ConfigResourceTypes(instanceOf(List.of(flaky, store(IRestAgentStore.resourceURI))));

        // First answer: the workflow store failed, so its type fails closed...
        assertFalse(types.isConfigType("ai.labs.workflow"));
        // ...but only until it answers: a partial set cached for good would turn every
        // workflow descriptor into a 404 until the next restart.
        assertTrue(types.isConfigType("ai.labs.workflow"));
        assertTrue(types.isConfigType("ai.labs.agent"));
    }

    @Test
    @DisplayName("an empty answer is not cached — it fails closed and is asked again")
    void emptyAnswerIsNotCached() {
        List<IRestVersionInfo> stores = new ArrayList<>();
        var types = new ConfigResourceTypes(instanceOf(stores));

        assertFalse(types.isConfigType("ai.labs.agent"));
        stores.add(store(IRestAgentStore.resourceURI));
        assertTrue(types.isConfigType("ai.labs.agent"));
    }

    @Test
    @DisplayName("of(...) is a fixed set")
    void fixedSet() {
        assertEquals(Set.of("ai.labs.agent"), ConfigResourceTypes.of("ai.labs.agent").types());
    }
}
