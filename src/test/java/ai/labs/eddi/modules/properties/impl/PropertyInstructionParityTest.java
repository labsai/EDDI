/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code property.json} and a {@code postResponse} property instruction run the
 * same instruction the same way — they share
 * {@link PropertyInstructionExecutor} now. Each case below goes through both
 * and must store the same typed value. Before, the {@code postResponse} side
 * stored {@code ""} for every non-string (live: a GitHub repository's
 * {@code stargazers_count} and {@code private}), and the property setter
 * dropped {@code Double} and {@code Long}.
 */
class PropertyInstructionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ITemplatingEngine templatingEngine;
    private IMemoryItemConverter memoryItemConverter;
    private Map<String, Object> response;

    @BeforeEach
    void setUp() throws Exception {
        templatingEngine = mock(ITemplatingEngine.class);
        when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
        memoryItemConverter = mock(IMemoryItemConverter.class);
        // The shape a GitHub repository response has once Jackson has read it.
        response = MAPPER.readValue("""
                {"full_name":"labsai/EDDI","stargazers_count":321,"private":false,"score":48.2081743,
                 "pushed_at_ms":1759400000000,"owner":{"login":"labsai"},"topics":["ai","chatbot"],
                 "raw":"{\\"a\\":1}"}""", Map.class);
    }

    static Stream<Arguments> fromObjectPathCases() {
        return Stream.of(Arguments.of("repo.full_name", (Function<Property, Object>) Property::getValueString, "labsai/EDDI"),
                Arguments.of("repo.stargazers_count", (Function<Property, Object>) Property::getValueInt, 321),
                Arguments.of("repo.private", (Function<Property, Object>) Property::getValueBoolean, false),
                Arguments.of("repo.score", (Function<Property, Object>) Property::getValueDouble, 48.2081743d),
                Arguments.of("repo.pushed_at_ms", (Function<Property, Object>) Property::getValueLong, 1759400000000L),
                Arguments.of("repo.owner", (Function<Property, Object>) Property::getValueObject, Map.of("login", "labsai")),
                Arguments.of("repo.topics", (Function<Property, Object>) Property::getValueList, List.of("ai", "chatbot")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fromObjectPathCases")
    @DisplayName("fromObjectPath stores the value exactly as found, on both paths")
    void fromObjectPathIsTypedOnBothPaths(String path, Function<Property, Object> slot, Object expected) throws Exception {
        var instruction = instruction("value");
        instruction.setFromObjectPath(path);

        Property viaSetter = viaPropertySetter(instruction);
        Property viaPostResponse = viaPostResponse(instruction);

        assertEquals(expected, slot.apply(viaSetter), "property.json");
        assertEquals(expected, slot.apply(viaPostResponse), "postResponse");
        assertEquals(viaSetter, viaPostResponse, "the two paths must store the same property");
    }

    @Test
    @DisplayName("a path that resolves to nothing writes nothing on both paths")
    void missingPathWritesNothing() throws Exception {
        var instruction = instruction("value");
        instruction.setFromObjectPath("repo.does_not_exist");

        assertNull(viaPropertySetter(instruction));
        assertNull(viaPostResponse(instruction));
    }

    @Test
    @DisplayName("override=false keeps an existing property on both paths")
    void overrideFalseIsHonouredOnBothPaths() throws Exception {
        var instruction = instruction("value");
        instruction.setFromObjectPath("repo.stargazers_count");
        instruction.setOverride(false);

        assertEquals("kept", viaPropertySetter(instruction, new Property("value", "kept", Scope.conversation)).getValueString());
        assertEquals("kept", viaPostResponse(instruction, new Property("value", "kept", Scope.conversation)).getValueString());
    }

    @Test
    @DisplayName("convertToObject parses a JSON-object string on both paths")
    void convertToObjectOnBothPaths() throws Exception {
        var instruction = instruction("value");
        instruction.setFromObjectPath("repo.raw");
        instruction.setConvertToObject(true);

        assertEquals(Map.of("a", 1), viaPropertySetter(instruction).getValueObject());
        assertEquals(Map.of("a", 1), viaPostResponse(instruction).getValueObject());
    }

    @Test
    @DisplayName("typed value fields are honoured by a postResponse instruction too")
    void typedValueFieldsOnBothPaths() throws Exception {
        var instruction = instruction("value");
        instruction.setValueDouble(19.99d);

        assertEquals(19.99d, viaPropertySetter(instruction).getValueDouble());
        assertEquals(19.99d, viaPostResponse(instruction).getValueDouble());
    }

    @Test
    @DisplayName("a BigDecimal is stored as a double")
    void bigDecimalIsStoredAsDouble() throws Exception {
        response.put("exact", new BigDecimal("12.50"));
        var instruction = instruction("value");
        instruction.setFromObjectPath("repo.exact");

        assertEquals(12.5d, viaPropertySetter(instruction).getValueDouble());
        assertEquals(12.5d, viaPostResponse(instruction).getValueDouble());
    }

    @Test
    @DisplayName("an inline property.json reads valueFloat 1.5, valueDouble and valueLong (Jackson's Double/Long were dropped)")
    void inlineConfigNumbersAreParsed() throws Exception {
        var task = newPropertySetterTask();
        Map<String, Object> config = MAPPER.readValue("""
                {"setOnActions":[{"actions":["go"],"setProperties":[
                  {"name":"f","valueFloat":1.5},
                  {"name":"d","valueDouble":48.2081743},
                  {"name":"l","valueLong":1759400000000},
                  {"name":"i","valueInt":3}]}]}""", Map.class);

        var setter = (IPropertySetter) task.configure(config, Map.of());
        var instructions = setter.getSetOnActionsList().getFirst().getSetProperties();

        assertEquals(1.5f, instructions.get(0).getValueFloat());
        assertEquals(48.2081743d, instructions.get(1).getValueDouble());
        assertEquals(1759400000000L, instructions.get(2).getValueLong());
        assertEquals(3, instructions.get(3).getValueInt());
    }

    @Test
    @DisplayName("an inline valueInt beyond the int range becomes valueLong instead of wrapping around")
    void inlineValueIntBeyondIntRangeIsNotWrapped() throws Exception {
        var task = newPropertySetterTask();
        Map<String, Object> config = MAPPER.readValue("""
                {"setOnActions":[{"actions":["go"],"setProperties":[
                  {"name":"big","valueInt":3000000000},
                  {"name":"neg","valueInt":-3000000000},
                  {"name":"small","valueInt":7}]}]}""", Map.class);

        var setter = (IPropertySetter) task.configure(config, Map.of());
        var instructions = setter.getSetOnActionsList().getFirst().getSetProperties();

        assertNull(instructions.get(0).getValueInt(), "3000000000 must not be wrapped to a negative int");
        assertEquals(3_000_000_000L, instructions.get(0).getValueLong());
        assertEquals(-3_000_000_000L, instructions.get(1).getValueLong());
        assertEquals(7, instructions.get(2).getValueInt());
        assertNull(instructions.get(2).getValueLong());
    }

    @Test
    @DisplayName("a path that reaches a non-JSON value sets nothing and does not fail the turn, on both paths")
    void nonJsonValueIsSkippedOnBothPaths() throws Exception {
        response.put("live", new Object());
        var instruction = instruction("value");
        instruction.setFromObjectPath("repo.live");

        assertNull(viaPropertySetter(instruction));
        assertNull(viaPostResponse(instruction));
    }

    // ------------------------------------------------------------------ helpers

    private static PropertyInstruction instruction(String name) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setScope(Scope.conversation);
        return instruction;
    }

    private Map<String, Object> templateData() {
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("repo", response);
        return templateData;
    }

    private Property viaPropertySetter(PropertyInstruction instruction, Property... existing) throws Exception {
        var memory = memory(existing);
        when(memoryItemConverter.convert(any())).thenReturn(templateData());
        memory.getCurrentStep().storeData(new Data<>("actions", List.of("go")));
        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("go"));
        setOnActions.setSetProperties(List.of(instruction));
        var propertySetter = mock(IPropertySetter.class);
        when(propertySetter.getSetOnActionsList()).thenReturn(List.of(setOnActions));
        when(propertySetter.extractProperties(any())).thenReturn(new LinkedList<>());

        newPropertySetterTask().execute(memory, propertySetter);
        return memory.getConversationProperties().get("value");
    }

    private Property viaPostResponse(PropertyInstruction instruction, Property... existing) throws Exception {
        var memory = memory(existing);
        var jsonSerialization = mock(IJsonSerialization.class);
        when(jsonSerialization.deserialize(anyString())).thenAnswer(invocation -> MAPPER.readValue((String) invocation.getArgument(0), Map.class));
        var prePostUtils = new PrePostUtils(jsonSerialization, memoryItemConverter, templatingEngine, new DataFactory(),
                mock(SecretPropertyVault.class));

        prePostUtils.executePropertyInstructions(List.of(instruction), 200, false, memory, templateData());
        return memory.getConversationProperties().get("value");
    }

    private static ConversationMemory memory(Property... existing) {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        for (Property property : existing) {
            memory.getConversationProperties().put(property.getName(), property);
        }
        return memory;
    }

    private PropertySetterTask newPropertySetterTask() {
        var expressionProvider = mock(IExpressionProvider.class);
        when(expressionProvider.parseExpressions(anyString())).thenReturn(new Expressions());
        return new PropertySetterTask(expressionProvider, memoryItemConverter, templatingEngine, new DataFactory(),
                mock(IResourceClientLibrary.class), MAPPER, mock(SecretPropertyVault.class));
    }
}
