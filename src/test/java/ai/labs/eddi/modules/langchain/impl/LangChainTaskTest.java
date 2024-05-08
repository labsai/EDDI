package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@QuarkusTest
class LangChainTaskTest {
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;

    private LangChainTask langChainTask;


    @BeforeEach
    void setUp() {
        openMocks(this);
        langChainTask = new LangChainTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine);
    }

    @Test
    void execute() throws Exception {
        // Setup
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        IData actionData = mock(IData.class);
        when(currentStep.getLatestData("actions")).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of("action1"));

        LangChainConfiguration langChainConfig = new LangChainConfiguration(List.of(
                new LangChainConfiguration.Task(
                        List.of("action1"), "taskId", "openai", "description",
                        Map.of("systemMessage", "Act as a real estate agent", "logSizeLimit", "10", "apiKey", "<apiKey>"))
        ));

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

        when(templatingEngine.processTemplate(anyString(), anyMap())).thenReturn("Processed template content");

        // Test
        langChainTask.execute(memory, langChainConfig);

        // Assert
        // Verify that the templating engine was called
        verify(templatingEngine, times(1)).processTemplate(anyString(), anyMap());

        // Verify that data was correctly added to the conversation step
        verify(currentStep, times(1)).storeData(any(IData.class));

        // Verify that the conversation output string was updated
        verify(currentStep, times(1)).
                addConversationOutputString(eq(LangChainTask.MEMORY_OUTPUT_IDENTIFIER), anyString());
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
        assertEquals(LangChainTask.ID, descriptor.getType(), "The ID should match the expected value.");
        assertEquals("Lang Chain", descriptor.getDisplayName(), "The display name should match 'Lang Chain'.");

        assertFalse(descriptor.getConfigs().isEmpty(), "Expected configs to be defined.");

        assertTrue(descriptor.getConfigs().containsKey("uri"), "Expected 'uri' config to be present.");
        var uriConfig = descriptor.getConfigs().get("uri");
        assertNotNull(uriConfig, "'uri' config should not be null.");
        assertEquals(ExtensionDescriptor.FieldType.URI, uriConfig.getFieldType(), "'uri' config type should be URI.");
    }

}