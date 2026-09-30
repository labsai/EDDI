/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A NEGOTIATION group saved with a prompt-less Arbitration phase is stored with
 * the preset's arbitration prompt, whichever client saved it: the REST and MCP
 * APIs and ZIP import all reach the store, only the Manager repaired it before.
 */
@DisplayName("AgentGroupStore — NEGOTIATION arbitration prompt on save")
class AgentGroupStoreNegotiationArbitrationTest {

    private static final int ARBITRATION = 3;

    private IResourceStorage<AgentGroupConfiguration> storage;
    private AgentGroupStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        storage = mock(IResourceStorage.class);
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        when(storageFactory.create(eq("groups"), any(), eq(AgentGroupConfiguration.class))).thenReturn((IResourceStorage) storage);
        store = new AgentGroupStore(storageFactory, mock(IDocumentBuilder.class));
    }

    private static AgentGroupConfiguration negotiation(String arbitrationTemplate) {
        List<DiscussionPhase> phases = new ArrayList<>(DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, 2));
        var p = phases.get(ARBITRATION);
        phases.set(ARBITRATION, new DiscussionPhase(p.name(), p.type(), p.participants(), p.turnOrder(), p.contextScope(),
                p.targetEachPeer(), arbitrationTemplate, p.repeats(), p.requiresApproval(), p.convergence(), p.allowAbstention(),
                p.voteConfig(), p.skipIf()));
        var config = new AgentGroupConfiguration();
        config.setName("Negotiation");
        config.setStyle(DiscussionStyle.NEGOTIATION);
        config.setMaxRounds(2);
        config.setModeratorAgentId("moderator");
        config.setMembers(List.of(new GroupMember("buyer", "Buyer", 1, null), new GroupMember("seller", "Seller", 2, null)));
        config.setPhases(phases);
        return config;
    }

    @Test
    @DisplayName("create stores the arbitration prompt")
    void createStoresThePrompt() throws Exception {
        store.create(negotiation(null));

        ArgumentCaptor<AgentGroupConfiguration> stored = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(storage).newResource(stored.capture());
        assertEquals(DiscussionStylePresets.TEMPLATE_ARBITRATION, stored.getValue().getPhases().get(ARBITRATION).inputTemplate());
        assertEquals(DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, 2), stored.getValue().getPhases());
    }

    @Test
    @DisplayName("the repair the update path runs fills the prompt in")
    void repairFillsThePrompt() {
        var config = negotiation(null);

        AgentGroupStore.repairNegotiationArbitration(config);

        assertEquals(DiscussionStylePresets.TEMPLATE_ARBITRATION, config.getPhases().get(ARBITRATION).inputTemplate());
    }

    @Test
    @DisplayName("an author's own arbitration prompt is stored as written")
    void authorPromptKept() throws Exception {
        store.create(negotiation("Decide: {question}"));

        ArgumentCaptor<AgentGroupConfiguration> stored = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(storage).newResource(stored.capture());
        assertEquals("Decide: {question}", stored.getValue().getPhases().get(ARBITRATION).inputTemplate());
    }

    @Test
    @DisplayName("a preset-style group that stores no phases is left without phases")
    void presetGroupWithoutPhasesUntouched() {
        var config = negotiation(null);
        config.setPhases(null);

        AgentGroupStore.repairNegotiationArbitration(config);

        assertNull(config.getPhases());
    }

    @Test
    @DisplayName("an intact list is not replaced")
    void intactListNotReplaced() {
        var config = negotiation(DiscussionStylePresets.TEMPLATE_ARBITRATION);
        var phases = config.getPhases();

        AgentGroupStore.repairNegotiationArbitration(config);

        assertSame(phases, config.getPhases());
    }
}
