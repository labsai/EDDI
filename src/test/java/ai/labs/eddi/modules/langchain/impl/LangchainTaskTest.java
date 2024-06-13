package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.httpcalls.impl.PrePostUtils;
import ai.labs.eddi.modules.langchain.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class LangchainTaskTest {
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;

    private LangchainTask langChainTask;

    private static final String TEST_MESSAGE_FROM_LLM = "Message from LLM";

    @BeforeEach
    void setUp() {
        openMocks(this);
        Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders = new HashMap<>();
        languageModelApiConnectorBuilders.put("openai",
                () -> parameters -> messages -> new Response<>(new AiMessage(TEST_MESSAGE_FROM_LLM)));

        var jsonSerialization = mock(IJsonSerialization.class);
        var prePostUtils = mock(PrePostUtils.class);
        langChainTask = new LangchainTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine,
                jsonSerialization, prePostUtils,
                languageModelApiConnectorBuilders);
    }

    static Stream<Arguments> provideParameters() {
        return Stream.of(
                Arguments.of(
                        Map.of(
                                "systemMessage", "Act as a real estate agent",
                                "logSizeLimit", "10",
                                "apiKey", "<apiKey>",
                                "addToOutput", "true"
                        ),
                        List.of(new TextOutputItem(TEST_MESSAGE_FROM_LLM, 0)),
                        4, // times for templatingEngine.processTemplate
                        2, // times for currentStep.storeData
                        1  // times for currentStep.addConversationOutputList
                ),
                Arguments.of(
                        Map.of(
                                "systemMessage", "Act as a real estate agent",
                                "logSizeLimit", "10",
                                "apiKey", "<apiKey1>"
                        ),
                        List.of(new TextOutputItem(TEST_MESSAGE_FROM_LLM, 0)),
                        3, // times for templatingEngine.processTemplate
                        1, // times for currentStep.storeData
                        0  // times for currentStep.addConversationOutputList
                )
                // Add more test cases as needed
        );
    }

    @ParameterizedTest
    @MethodSource("provideParameters")
    void execute(Map<String, String> parameters, List<TextOutputItem> expectedOutput,
                 int timesTemplate, int timesStoreData, int timesAddOutputList) throws Exception {
        // Setup
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "hi");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

        var actionData = mock(IData.class);
        when(currentStep.getLatestData("actions")).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of("action1"));

        var langChainConfig = new LangChainConfiguration(List.of(
                new LangChainConfiguration.Task(
                        List.of("action1"), "taskId", "openai", "description",
                        null, null, parameters)
        ));

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

        // Adding debug statements
        System.out.println("Setting up mocks with parameters:");
        parameters.forEach((key, value) -> System.out.println(key + ": " + value));

        var systemMessage = parameters.getOrDefault("systemMessage", "-1");
        var apiKey = parameters.getOrDefault("apiKey", "-1");
        var logSizeLimit = parameters.getOrDefault("logSizeLimit", "-1");
        var addToOutput = parameters.getOrDefault("addToOutput", "false");
        var convertToObject = parameters.getOrDefault("convertToObject", "false");
        when(templatingEngine.processTemplate(eq(systemMessage), anyMap())).thenReturn(systemMessage);
        when(templatingEngine.processTemplate(eq(apiKey), anyMap())).thenReturn(apiKey);
        when(templatingEngine.processTemplate(eq(logSizeLimit), anyMap())).thenReturn(logSizeLimit);
        when(templatingEngine.processTemplate(eq(addToOutput), anyMap())).thenReturn(addToOutput);
        when(templatingEngine.processTemplate(eq(convertToObject), anyMap())).thenReturn(convertToObject);

        // Test
        langChainTask.execute(memory, langChainConfig);

        // Assert
        // Verify that the templating engine was called
        verify(templatingEngine, times(timesTemplate)).processTemplate(anyString(), anyMap());

        // Verify that data was correctly added to the conversation step
        verify(currentStep, times(timesStoreData)).storeData(any(IData.class));

        // Verify that the conversation output string was updated
        verify(currentStep, times(timesAddOutputList)).
                addConversationOutputList(eq(LangchainTask.MEMORY_OUTPUT_IDENTIFIER), eq(expectedOutput));
    }


    @Test
    void configure() throws Exception {
        // Setup
        String uriValue = "http://example.com/config";
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("uri", uriValue);

        LangChainConfiguration expectedConfig = new LangChainConfiguration(List.of(new LangChainConfiguration.Task(
                List.of("action1", "action2"),
                "taskId",
                "taskType",
                "A task description",
                null, null,
                Map.of("key", "value")
        )));

        when(resourceClientLibrary.getResource(any(URI.class), eq(LangChainConfiguration.class))).thenReturn(expectedConfig);

        // Test
        Object result = langChainTask.configure(configuration, Collections.emptyMap());

        // Assert
        assertNotNull(result, "Configuration result should not be null.");
        assertInstanceOf(LangChainConfiguration.class, result, "Result should be an instance of LangChainConfiguration.");
        LangChainConfiguration configResult = (LangChainConfiguration) result;
        assertEquals(expectedConfig.tasks().size(), configResult.tasks().size(), "Task sizes should match.");
        assertEquals(expectedConfig.tasks().getFirst().id(), configResult.tasks().getFirst().id(), "Task IDs should match.");
    }


    @Test
    void testGetExtensionDescriptor() {
        ExtensionDescriptor descriptor = langChainTask.getExtensionDescriptor();

        assertNotNull(descriptor, "The returned ExtensionDescriptor should not be null.");

        // Assuming ID and Display Name are known and static for the LangChainTask
        assertEquals(LangchainTask.ID, descriptor.getType(), "The ID should match the expected value.");
        assertEquals("Lang Chain", descriptor.getDisplayName(), "The display name should match 'Lang Chain'.");

        assertFalse(descriptor.getConfigs().isEmpty(), "Expected configs to be defined.");

        assertTrue(descriptor.getConfigs().containsKey("uri"), "Expected 'uri' config to be present.");
        var uriConfig = descriptor.getConfigs().get("uri");
        assertNotNull(uriConfig, "'uri' config should not be null.");
        assertEquals(ExtensionDescriptor.FieldType.URI, uriConfig.getFieldType(), "'uri' config type should be URI.");
    }
}