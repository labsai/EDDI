/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H8 — the builder parameters of an LLM task are resolved against the vault
 * after templating ({@code ChatModelRegistry}), so a {@code ${vault:…}} that
 * conversation data put into one — {@code "modelName": "{context.model}"} and a
 * user who sends {@code ${vault:another-agents-key}} — used to be resolved with
 * no grant check and handed to the provider client.
 */
@DisplayName("LlmTask — references in rendered LLM parameters")
class LlmTaskParameterReferenceGuardTest {

    private static final Map<String, Object> DATA = Map.of("conversationInfo", Map.of("agentId", "agent1", "conversationId", "conv1"));

    /** Renders like runTemplateEngineOnParams: escape, then Qute. */
    private static Map<String, String> render(Map<String, String> configured, Map<String, Object> data) throws Exception {
        var engine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
        var rendered = new HashMap<String, String>();
        for (var entry : configured.entrySet()) {
            rendered.put(entry.getKey(), engine.processTemplate(LlmTask.escapeConfigReferenceMentions(entry.getValue()), data));
        }
        return rendered;
    }

    private static Map<String, Object> dataWith(String contextModel) {
        var data = new HashMap<String, Object>(DATA);
        data.put("context", Map.of("model", contextModel));
        return data;
    }

    @Test
    @DisplayName("a vault reference typed into a templated builder parameter is refused before it can be resolved")
    void injectedVaultReferenceRefused() throws Exception {
        var configured = Map.of("modelName", "{context.model}");
        var data = dataWith("${vault:another-agents-key}");

        var e = assertThrows(LifecycleException.class,
                () -> LlmTask.guardRenderedParameters(configured, render(configured, data), data, Map.of()));
        assertTrue(e.getMessage().contains("LLM parameter 'modelName' contains the reference ${vault:another-agents-key}"), e.getMessage());
    }

    @Test
    @DisplayName("a data-supplied global variable reference is refused — it can expand to a vault reference")
    void injectedVarsReferenceRefused() throws Exception {
        var configured = Map.of("baseUrl", "{context.model}");
        var data = dataWith("${vars:credential}");

        assertThrows(LifecycleException.class, () -> LlmTask.guardRenderedParameters(configured, render(configured, data), data, Map.of()));
    }

    @Test
    @DisplayName("references the configuration wrote are resolved as before")
    void configuredReferencesAllowed() throws Exception {
        var configured = Map.of("apiKey", "${vault:openai-key}", "modelName", "${vars:default-model}", "baseUrl", "https://{context.model}/v1");
        var data = dataWith("api.example.com");

        assertDoesNotThrow(() -> LlmTask.guardRenderedParameters(configured, render(configured, data), data, Map.of()));
    }

    @Test
    @DisplayName("the prompts may carry reference-shaped text from the conversation: they are never resolved")
    void promptsAreNotGuarded() throws Exception {
        var configured = Map.of("systemMessage", "The user said: {context.model}", "prompt", "{context.model}");
        var data = dataWith("${vault:whatever}");

        assertDoesNotThrow(() -> LlmTask.guardRenderedParameters(configured, render(configured, data), data, Map.of()));
    }

    @Test
    @DisplayName("this conversation's auto-vaulted property, named by the template, is allowed")
    void autoVaultedPropertyAllowed() throws Exception {
        var property = new Property("endpointKey", "${vault:agent1.conv1.endpointKey}", Scope.conversation);
        property.setAutoVaulted(Boolean.TRUE);
        var configured = Map.of("customHeader", "{properties.endpointKey}");
        var data = new HashMap<String, Object>(DATA);
        data.put("properties", Map.of("endpointKey", "${vault:agent1.conv1.endpointKey}"));

        assertDoesNotThrow(() -> LlmTask.guardRenderedParameters(configured, render(configured, data), data, Map.of("endpointKey", property)));
    }
}
