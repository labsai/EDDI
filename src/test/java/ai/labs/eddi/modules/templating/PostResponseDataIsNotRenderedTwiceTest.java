/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.OutputBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Upstream content (an API response, a model's reply, an MCP result) that a
 * {@code postResponse} turns into properties, output and quick replies is data.
 * It is rendered at most once — as the value substituted into the author's
 * template — and never evaluated as a template itself, neither when it is
 * stored nor when the templating task later processes the turn's output.
 * <p>
 * Runs the real pipeline pieces on a real conversation memory:
 * {@link PrePostUtils} → {@link OutputGenerationTask} →
 * {@link OutputTemplateTask}, all with a real template engine.
 */
@DisplayName("postResponse data is never rendered as a template")
class PostResponseDataIsNotRenderedTwiceTest {

    /**
     * Upstream text carrying template syntax that would expose the token if
     * evaluated.
     */
    private static final String UPSTREAM = "result {properties.apiToken} {#for i in 3}x{/for}";

    private IConversationMemory memory;
    private Map<String, Object> templateData;
    private TemplatingEngine templatingEngine;
    private DataFactory dataFactory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("0123456789abcdef01234567", 1, "user-1");
        memory.getConversationProperties().put("apiToken", new Property("apiToken", "tok-7f3a", Property.Scope.conversation));

        templateData = new HashMap<>();
        templateData.put("properties", memory.getConversationProperties().toMap());
        templateData.put("apiResponse", Map.of("note", UPSTREAM, "items", List.of(Map.of("text", UPSTREAM))));

        templatingEngine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
        dataFactory = new DataFactory();
    }

    private PrePostUtils prePostUtils() {
        return new PrePostUtils(mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), templatingEngine, dataFactory);
    }

    private static PostResponse postResponse() {
        var property = new PropertyInstruction();
        property.setName("note");
        property.setFromObjectPath("apiResponse.note");
        property.setScope(Property.Scope.conversation);

        var output = new OutputBuildingInstruction();
        output.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        output.setOutputType("text");
        output.setIterationObjectName("item");
        output.setPathToTargetArray("apiResponse.items");
        output.setOutputValue("Found: {item.text}");

        var quickReply = new QuickRepliesBuildingInstruction();
        quickReply.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
        quickReply.setIterationObjectName("item");
        quickReply.setPathToTargetArray("apiResponse.items");
        quickReply.setQuickReplyValue("{item.text}");
        quickReply.setQuickReplyExpressions("pick");

        var postResponse = new PostResponse();
        postResponse.setPropertyInstructions(List.of(property));
        postResponse.setOutputBuildInstructions(List.of(output));
        postResponse.setQrBuildInstructions(List.of(quickReply));
        return postResponse;
    }

    @Test
    @DisplayName("a property read from the response is stored verbatim")
    void propertyIsStoredVerbatim() throws Exception {
        prePostUtils().runPostResponse(memory, postResponse(), templateData, 200, false);

        assertEquals(UPSTREAM, memory.getConversationProperties().get("note").getValueString());
    }

    @Test
    @DisplayName("output and quick replies built from the response reach the user verbatim")
    void outputIsRenderedOnce() throws Exception {
        prePostUtils().runPostResponse(memory, postResponse(), templateData, 200, false);

        // An output item authored in an output configuration, in the same turn, is
        // still a template and must still be rendered.
        var authored = new Data<Object>("output:text:greet", new TextOutputItem("Hi {properties.apiToken}"));
        memory.getCurrentStep().storeData(authored);

        new OutputGenerationTask(mock(IResourceClientLibrary.class), dataFactory, new ObjectMapper()).execute(memory, null);

        var converter = mock(IMemoryItemConverter.class);
        when(converter.convert(memory)).thenReturn(templateData);
        new OutputTemplateTask(templatingEngine, converter, dataFactory, new ObjectMapper()).execute(memory, null);

        IData<Object> contextOutput = memory.getCurrentStep().getLatestData("output:text:context");
        assertNotNull(contextOutput, "the postResponse output must have been stored");
        assertEquals("Found: " + UPSTREAM, ((TextOutputItem) contextOutput.getResult()).getText());
        assertTrue(contextOutput.isVerbatim());

        IData<List<QuickReply>> quickReplies = memory.getCurrentStep().getLatestData("quickReplies:context");
        assertNotNull(quickReplies, "the postResponse quick replies must have been stored");
        assertEquals(UPSTREAM, quickReplies.getResult().getFirst().getValue());

        assertEquals("Hi tok-7f3a", ((TextOutputItem) authored.getResult()).getText());
    }
}
