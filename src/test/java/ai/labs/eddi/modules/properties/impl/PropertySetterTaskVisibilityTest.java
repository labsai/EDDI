/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A property instruction's {@code visibility} must reach the property it
 * produces — it is what the persistence boundary reads for {@code longTerm}
 * properties. Every branch used to build
 * {@code new Property(name, value, scope)} and drop it, so a
 * {@code "visibility": "self"} property was stored as {@code global} and read
 * by every other agent of the user.
 */
class PropertySetterTaskVisibilityTest {

    private PropertySetterTask task;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        var expressionProvider = mock(IExpressionProvider.class);
        when(expressionProvider.parseExpressions(anyString())).thenReturn(new Expressions());
        var memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenReturn(new HashMap<>());
        var templatingEngine = mock(ITemplatingEngine.class);
        when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));

        task = new PropertySetterTask(expressionProvider, memoryItemConverter, templatingEngine, new DataFactory(),
                mock(IResourceClientLibrary.class), new ObjectMapper(), new SecretPropertyVault(mock(ISecretProvider.class), new DataFactory()));
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("actions", List.of("set")));
    }

    private static PropertyInstruction instruction(String name, Visibility visibility) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setValueString("v");
        instruction.setScope(Scope.longTerm);
        instruction.setVisibility(visibility);
        instruction.setOverride(true);
        return instruction;
    }

    private IPropertySetter setter(PropertyInstruction... instructions) {
        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("set"));
        setOnActions.setSetProperties(List.of(instructions));
        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());
        return propertySetter;
    }

    @Test
    @DisplayName("each instruction's visibility is carried onto its property; none stays null (the agent default applies later)")
    void visibilityReachesTheProperty() throws Exception {
        task.execute(memory, setter(instruction("mine", Visibility.self), instruction("team", Visibility.group),
                instruction("shared", Visibility.global), instruction("unset", null)));

        var properties = memory.getConversationProperties();
        assertEquals(Visibility.self, properties.get("mine").getVisibility());
        assertEquals(Visibility.group, properties.get("team").getVisibility());
        assertEquals(Visibility.global, properties.get("shared").getVisibility());
        assertNull(properties.get("unset").getVisibility());
    }

    @Test
    @DisplayName("visibility is carried for non-string values too")
    void visibilityForObjectValues() throws Exception {
        var instruction = instruction("obj", Visibility.self);
        instruction.setValueString(null);
        instruction.setValueObject(Map.of("a", 1));

        task.execute(memory, setter(instruction));

        assertEquals(Visibility.self, memory.getConversationProperties().get("obj").getVisibility());
    }

    @Test
    @DisplayName("an inline setOnActions config keeps visibility (the typed URI path always did)")
    void inlineConfigParsesVisibility() throws Exception {
        Map<String, Object> config = Map.of("setOnActions", List.of(Map.of("actions", List.of("set"), "setProperties",
                List.of(Map.of("name", "k", "valueString", "v", "scope", "longTerm", "visibility", "group")))));

        var propertySetter = (IPropertySetter) task.configure(config, Map.of());

        assertEquals(Visibility.group, propertySetter.getSetOnActionsList().getFirst().getSetProperties().getFirst().getVisibility());
    }

    @Test
    @DisplayName("an inline config with an unknown visibility fails loudly instead of silently storing it as global")
    void inlineConfigRejectsUnknownVisibility() {
        Map<String, Object> config = Map.of("setOnActions", List.of(Map.of("actions", List.of("set"), "setProperties",
                List.of(Map.of("name", "k", "valueString", "v", "scope", "longTerm", "visibility", "everyone")))));

        var e = assertThrows(IllegalArgumentException.class, () -> task.configure(config, Map.of()));
        assertTrue(e.getMessage().contains("everyone"), e.getMessage());
    }
}
