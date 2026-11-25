/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCard;
import ai.labs.eddi.engine.a2a.A2AModels.InboundFileMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Agent Card advertises file media types as input modes only when inbound
 * file parts are switched on ({@code eddi.a2a.files.inbound}).
 */
class AgentCardFileInputModesTest {

    private static AgentCard cardWith(InboundFileMode mode) throws Exception {
        IAgentStore agentStore = mock(IAgentStore.class);
        var config = new AgentConfiguration();
        config.setA2aEnabled(true);
        var resourceId = new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return "agent-1";
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
        when(agentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
        when(agentStore.read("agent-1", 1)).thenReturn(config);
        var service = new AgentCardService(agentStore, mock(IDocumentDescriptorStore.class), "http://localhost:7070", false, Optional.empty(),
                Optional.empty(), Optional.empty(), "unknown", () -> ResolvedBrand.DEFAULT_PRODUCT_NAME, mode);
        return service.getAgentCard("agent-1");
    }

    @ParameterizedTest
    @EnumSource(InboundFileMode.class)
    @DisplayName("off: text and JSON only, as before; inline / inline-and-uri: plus the file types the forwarder takes")
    void inputModes(InboundFileMode mode) throws Exception {
        List<String> expected = mode == InboundFileMode.OFF
                ? List.of("text/plain", "application/json")
                : Stream.concat(Stream.of("text/plain", "application/json"),
                        Stream.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp")).toList();

        AgentCard card = cardWith(mode);

        assertEquals(expected, card.defaultInputModes());
        assertEquals(List.of("text/plain"), card.defaultOutputModes(), "output modes do not change");
    }
}
