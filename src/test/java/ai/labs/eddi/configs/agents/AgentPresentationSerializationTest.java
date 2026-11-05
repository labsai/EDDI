/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentAppearance;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.ConversationControls;
import ai.labs.eddi.configs.agents.model.TitleGeneration;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.PersistenceMapperProducer;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZIP export/import and agent sync carry agent configurations as their JSON
 * (the same pattern as the catalog's {@code ListedFlagSerializationTest}), so
 * the presentation fields survive them exactly when they survive a JSON round
 * trip — through a plain mapper (what a ZIP holds and a sync sends) and through
 * the persistence mapper (what the store writes). A document written before the
 * fields existed reads with none of them.
 */
@DisplayName("appearance, conversationControls and titleGeneration — JSON round trip")
class AgentPresentationSerializationTest {

    private static AgentConfiguration full() {
        var appearance = new AgentAppearance();
        appearance.setDisplayName("Ava");
        appearance.setAvatar("https://cdn.example.com/ava.png");
        appearance.setAccentColor("#4f46e5");
        appearance.setStarters(List.of("Track my order", "Talk to a human"));
        var controls = new ConversationControls();
        controls.setUndo(false);
        controls.setRawHtml(true);
        var titles = new TitleGeneration();
        titles.setEnabled(true);
        titles.setLlmProvider("openai");
        titles.setLlmModel("gpt-4o-mini");
        titles.setParameters(Map.of("apiKey", "${vault:openai-key}"));
        titles.setMaxLength(40);
        var configuration = new AgentConfiguration();
        configuration.setAppearance(appearance);
        configuration.setConversationControls(controls);
        configuration.setTitleGeneration(titles);
        return configuration;
    }

    private static void assertFull(AgentConfiguration read) {
        assertEquals("Ava", read.getAppearance().getDisplayName());
        assertEquals("https://cdn.example.com/ava.png", read.getAppearance().getAvatar());
        assertEquals("#4f46e5", read.getAppearance().getAccentColor());
        assertEquals(List.of("Track my order", "Talk to a human"), read.getAppearance().getStarters());
        assertEquals(Boolean.FALSE, read.getConversationControls().getUndo());
        assertNull(read.getConversationControls().getRedo(), "an absent switch stays absent");
        assertEquals(Boolean.TRUE, read.getConversationControls().getRawHtml());
        assertTrue(read.getTitleGeneration().isEnabled());
        assertEquals("gpt-4o-mini", read.getTitleGeneration().getLlmModel());
        assertEquals("${vault:openai-key}", read.getTitleGeneration().getParameters().get("apiKey"),
                "a vault reference travels as the reference, never resolved");
        assertEquals(40, read.getTitleGeneration().getMaxLength());
    }

    @Test
    @DisplayName("a plain mapper (ZIP, sync) round-trips every field")
    void plainMapper() throws Exception {
        var mapper = new ObjectMapper();
        assertFull(mapper.readValue(mapper.writeValueAsString(full()), AgentConfiguration.class));
    }

    @Test
    @DisplayName("the ZIP export's secret scrub keeps every presentation field (and the vault reference)")
    void exportScrub() throws Exception {
        var mapper = new ObjectMapper();
        // RestExportService writes the agent through SecretScrubber.scrubJson.
        String exported = new SecretScrubber(mapper).scrubJson(mapper.writeValueAsString(full()));
        assertFull(mapper.readValue(exported, AgentConfiguration.class));
    }

    @Test
    @DisplayName("the persistence mapper (the store) round-trips every field")
    void persistenceMapper() throws Exception {
        var serialization = new JsonSerialization(new PersistenceMapperProducer().persistenceMapper());
        assertFull(serialization.deserialize(serialization.serialize(full()), AgentConfiguration.class));
    }

    @Test
    @DisplayName("an agent stored before the fields existed reads with none of them — and rawHtml therefore off")
    void legacyDocument() throws Exception {
        var read = new ObjectMapper().readValue("{\"workflows\":[]}", AgentConfiguration.class);
        assertNull(read.getAppearance());
        assertNull(read.getConversationControls());
        assertNull(read.getTitleGeneration());
        assertFalse(new TitleGeneration().isEnabled(), "titleGeneration is off unless enabled");
    }

    @Test
    @DisplayName("a conversation descriptor round-trips its title and pin; an old one reads untitled and unpinned")
    void descriptor() throws Exception {
        var mapper = new ObjectMapper();
        var descriptor = new ConversationDescriptor();
        descriptor.setTitle("Trip to Rome");
        descriptor.setTitleSource(ConversationDescriptor.TitleSource.USER);
        descriptor.setPinned(true);

        var read = mapper.readValue(mapper.writeValueAsString(descriptor), ConversationDescriptor.class);
        assertEquals("Trip to Rome", read.getTitle());
        assertEquals(ConversationDescriptor.TitleSource.USER, read.getTitleSource());
        assertTrue(read.isPinned());

        var legacy = mapper.readValue("{\"userId\":\"alice\"}", ConversationDescriptor.class);
        assertNull(legacy.getTitle());
        assertFalse(legacy.isPinned());
    }
}
