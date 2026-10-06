/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An LLM task's {@code postResponse} reading its own response object through
 * {@code fromObjectPath}. With {@code convertToObject: true} the task stores
 * the parsed response (a Map) in the template data under
 * {@code responseObjectName}; a property instruction naming that object used to
 * store an empty string, because every value reached through the path that was
 * not a String was replaced by {@code ""}.
 */
class PrePostUtilsFromObjectPathObjectTest {

    private PrePostUtils prePostUtils;
    private ConversationMemory memory;
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() throws Exception {
        var templatingEngine = mock(ITemplatingEngine.class);
        when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
        prePostUtils = new PrePostUtils(mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), templatingEngine, new DataFactory(),
                new SecretPropertyVault(mock(ISecretProvider.class), new DataFactory()));
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        templateData = new HashMap<>();
        Map<String, Object> aiOutput = new HashMap<>();
        aiOutput.put("htmlResponseText", "<p>hello</p>");
        aiOutput.put("sources", List.of("a", "b"));
        aiOutput.put("meta", Map.of("lang", "de"));
        templateData.put("aiOutput", aiOutput);
    }

    private static ITemplatingEngine passThroughEngine() throws Exception {
        var engine = mock(ITemplatingEngine.class);
        when(engine.processTemplate(anyString(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
        return engine;
    }

    private static PropertyInstruction instruction(String name, String path, boolean convertToObject) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setFromObjectPath(path);
        instruction.setConvertToObject(convertToObject);
        instruction.setScope(Scope.conversation);
        return instruction;
    }

    @Test
    @DisplayName("the exact LLM postResponse config: fromObjectPath naming the converted response object keeps the object")
    void responseObjectIsKept() throws Exception {
        prePostUtils.executePropertyInstructions(List.of(instruction("aiOutputObject", "aiOutput", true)), 200, false, memory, templateData);

        Property stored = memory.getConversationProperties().get("aiOutputObject");
        assertNotNull(stored);
        var map = assertInstanceOf(Map.class, stored.getValueObject());
        assertEquals("<p>hello</p>", map.get("htmlResponseText"));
        // what {properties.aiOutputObject.htmlResponseText} resolves against
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) templateData.get("properties");
        @SuppressWarnings("unchecked")
        var resolved = (Map<String, Object>) properties.get("aiOutputObject");
        assertEquals("<p>hello</p>", resolved.get("htmlResponseText"));
    }

    @Test
    @DisplayName("convertToObject false keeps the object as well")
    void objectKeptWithoutConvert() throws Exception {
        prePostUtils.executePropertyInstructions(List.of(instruction("o", "aiOutput", false)), 200, false, memory, templateData);

        assertInstanceOf(Map.class, memory.getConversationProperties().get("o").getValueObject());
    }

    @Test
    @DisplayName("a nested path resolves: a string leaf, a nested object and a list")
    void nestedPaths() throws Exception {
        prePostUtils.executePropertyInstructions(List.of(instruction("html", "aiOutput.htmlResponseText", true),
                instruction("meta", "aiOutput.meta", true), instruction("sources", "aiOutput.sources", true)), 200, false, memory, templateData);

        assertEquals("<p>hello</p>", memory.getConversationProperties().get("html").getValueString());
        assertEquals("de", ((Map<?, ?>) memory.getConversationProperties().get("meta").getValueObject()).get("lang"));
        assertEquals(List.of("a", "b"), memory.getConversationProperties().get("sources").getValueList());
    }

    @Test
    @DisplayName("a missing path does not throw and never yields an object")
    void missingPath() {
        assertDoesNotThrow(() -> prePostUtils.executePropertyInstructions(List.of(instruction("gone", "aiOutput.nope.deeper", true)), 200,
                false, memory, templateData));

        Property stored = memory.getConversationProperties().get("gone");
        if (stored != null) {
            assertEquals("", stored.getValueString());
        }
    }

    @Test
    @DisplayName("httpcalls regression: a JSON-object string from the response is still parsed with convertToObject")
    void httpCallsJsonStringStillParsed() throws Exception {
        var json = mock(IJsonSerialization.class);
        when(json.deserialize("{\"a\":1}")).thenReturn(Map.of("a", 1));
        var utils = new PrePostUtils(json, mock(IMemoryItemConverter.class), passThroughEngine(), new DataFactory(),
                new SecretPropertyVault(mock(ISecretProvider.class), new DataFactory()));
        templateData.put("httpResponse", Map.of("payload", "{\"a\":1}", "n", 5));

        utils.executePropertyInstructions(List.of(instruction("parsed", "httpResponse.payload", true), instruction("num", "httpResponse.n", false)),
                200, false, memory, templateData);

        assertEquals(1, ((Map<?, ?>) memory.getConversationProperties().get("parsed").getValueObject()).get("a"));
        // unchanged documented behaviour: a number reached through the path is stored
        // as ""
        assertEquals("", memory.getConversationProperties().get("num").getValueString());
    }
}
