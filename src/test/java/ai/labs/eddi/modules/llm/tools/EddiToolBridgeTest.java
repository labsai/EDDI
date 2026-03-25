package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import org.junit.jupiter.api.*;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EddiToolBridge.
 * Tests the bridge between LangChain agents and EDDI's httpcall system.
 */
class EddiToolBridgeTest {

    @Mock
    private IConversationMemoryStore conversationMemoryStore;

    @Mock
    private IResourceClientLibrary resourceClientLibrary;

    @Mock
    private IJsonSerialization jsonSerialization;

    @Mock
    private IApiCallExecutor httpCallExecutor;

    @Mock
    private ToolExecutionService toolExecutionService;

    @InjectMocks
    private EddiToolBridge eddiToolBridge;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Set conversation context via ThreadLocal (as AgentOrchestrator would)
        EddiToolBridge.setCurrentConversationId("conv-123");
    }

    @AfterEach
    void tearDown() {
        EddiToolBridge.clearCurrentConversationId();
    }

    @Nested
    @DisplayName("Successful Execution Tests")
    class SuccessfulExecutionTests {

        @Test
        @DisplayName("Should execute HTTP call successfully and return result")
        void testExecuteApiCall_Success() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/weather?version=1";

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            ApiCall httpCall = new ApiCall();
            httpCall.setName("Get Weather");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(URI.create(httpCallUri), ApiCallsConfiguration.class))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            Map<String, Object> executionResult = Map.of("temperature", 20, "humidity", 65);
            when(httpCallExecutor.execute(eq(httpCall), any(), anyMap(), eq("http://localhost:8080")))
                    .thenReturn(executionResult);
            when(jsonSerialization.serialize(executionResult)).thenReturn("{\"temperature\": 20, \"humidity\": 65}");

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertEquals("{\"temperature\": 20, \"humidity\": 65}", result);
            verify(resourceClientLibrary).getResource(URI.create(httpCallUri), ApiCallsConfiguration.class);
            verify(httpCallExecutor).execute(eq(httpCall), any(), anyMap(), eq("http://localhost:8080"));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return error when no conversation context is set")
        void testExecuteApiCall_NoConversationContext() throws Exception {
            EddiToolBridge.clearCurrentConversationId();

            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"No conversation context\"}");

            String result = eddiToolBridge.executeApiCall("eddi://ai.labs.apicalls/test?version=1");

            assertTrue(result.contains("No conversation context"));
        }

        @Test
        @DisplayName("Should return error when configuration not found")
        void testExecuteApiCall_ConfigNotFound() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/unknown?version=1";

            when(resourceClientLibrary.getResource(URI.create(httpCallUri), ApiCallsConfiguration.class))
                    .thenReturn(null);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"ApiCalls configuration not found\"}");

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("Should return error when no httpcalls in configuration")
        void testExecuteApiCall_NoApiCallsInConfig() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/empty?version=1";

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            config.setHttpCalls(List.of());

            when(resourceClientLibrary.getResource(any(), eq(ApiCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(jsonSerialization.serialize(anyMap()))
                    .thenAnswer(inv -> {
                        Map<String, Object> map = inv.getArgument(0);
                        return "{\"error\": true, \"message\": \"" + map.get("message") + "\"}";
                    });

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertTrue(result.contains("error"));
            assertTrue(result.contains("No httpcalls found"));
        }

        @Test
        @DisplayName("Should return error when resource loading throws ServiceException")
        void testExecuteApiCall_ServiceException() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/failing?version=1";

            when(resourceClientLibrary.getResource(any(), eq(ApiCallsConfiguration.class)))
                    .thenThrow(new ServiceException("Connection refused"));
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"Error loading configuration\"}");

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("Should return error when HTTP call execution fails")
        void testExecuteApiCall_ExecutionFailure() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/failing?version=1";

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            ApiCall httpCall = new ApiCall();
            httpCall.setName("Failing Call");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(ApiCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenThrow(new RuntimeException("Request timeout"));
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"Execution error: Request timeout\"}");

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertTrue(result.contains("error"));
        }
    }

    @Nested
    @DisplayName("Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("Should cache configuration after first load")
        void testConfigurationCaching() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/cached?version=1";

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            ApiCall httpCall = new ApiCall();
            httpCall.setName("Cached Call");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(ApiCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenReturn(Map.of("result", "cached"));
            when(jsonSerialization.serialize(any())).thenReturn("{\"result\": \"cached\"}");

            // Call twice
            eddiToolBridge.executeApiCall(httpCallUri);
            eddiToolBridge.executeApiCall(httpCallUri);

            // resourceClientLibrary should be called only once (cached)
            verify(resourceClientLibrary, times(1)).getResource(any(), eq(ApiCallsConfiguration.class));
        }
    }

    @Nested
    @DisplayName("Empty Input Tests")
    class EmptyInputTests {

        @Test
        @DisplayName("Should work with no template arguments")
        void testEmptyArguments() throws Exception {
            String httpCallUri = "eddi://ai.labs.apicalls/noargs?version=1";

            ApiCallsConfiguration config = new ApiCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            ApiCall httpCall = new ApiCall();
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(ApiCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenReturn(Map.of("success", true));
            when(jsonSerialization.serialize(any())).thenReturn("{\"success\": true}");

            String result = eddiToolBridge.executeApiCall(httpCallUri);

            assertNotNull(result);
            assertEquals("{\"success\": true}", result);
        }
    }
}
