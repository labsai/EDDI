package ai.labs.eddi.modules.langchain.tools;

import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCall;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.httpcalls.impl.IHttpCallExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
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
    private IHttpCallsStore httpCallsStore;

    @Mock
    private IConversationMemoryStore conversationMemoryStore;

    @Mock
    private IResourceClientLibrary resourceClientLibrary;

    @Mock
    private IMemoryItemConverter memoryItemConverter;

    @Mock
    private IJsonSerialization jsonSerialization;

    @Mock
    private IHttpCallExecutor httpCallExecutor;

    @Mock
    private ToolExecutionService toolExecutionService;

    @InjectMocks
    private EddiToolBridge eddiToolBridge;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Mock ToolExecutionService to invoke the method directly
        when(toolExecutionService.executeTool(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Method method = invocation.getArgument(1);
                    Object[] args = invocation.getArgument(2);
                    return method.invoke(invocation.getArgument(0), args);
                });
    }

    @Nested
    @DisplayName("Successful Execution Tests")
    class SuccessfulExecutionTests {

        @Test
        @DisplayName("Should execute HTTP call successfully and return result")
        void testExecuteHttpCall_Success() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/weather?version=1";
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("city", "London");

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            HttpCall httpCall = new HttpCall();
            httpCall.setName("Get Weather");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(URI.create(httpCallUri), HttpCallsConfiguration.class))
                    .thenReturn(config);

            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());

            Map<String, Object> executionResult = new HashMap<>();
            executionResult.put("temperature", 20);
            executionResult.put("humidity", 65);
            when(httpCallExecutor.execute(eq(httpCall), any(), anyMap(), eq("http://localhost:8080")))
                    .thenReturn(executionResult);

            when(jsonSerialization.serialize(executionResult)).thenReturn("{\"temperature\": 20, \"humidity\": 65}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertEquals("{\"temperature\": 20, \"humidity\": 65}", result);
            verify(resourceClientLibrary).getResource(URI.create(httpCallUri), HttpCallsConfiguration.class);
            verify(httpCallExecutor).execute(eq(httpCall), any(), anyMap(), eq("http://localhost:8080"));
        }

        @Test
        @DisplayName("Should merge arguments with template data")
        void testExecuteHttpCall_ArgumentsMerged() throws Exception {
            // Arrange
            String conversationId = "conv-456";
            String httpCallUri = "eddi://ai.labs.httpcalls/api?version=1";
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("param1", "value1");
            arguments.put("param2", "value2");

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://api.example.com");
            HttpCall httpCall = new HttpCall();
            httpCall.setName("API Call");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), any(), anyMap(), anyString()))
                    .thenReturn(Map.of("status", "ok"));
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\": \"ok\"}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertNotNull(result);
            verify(httpCallExecutor).execute(any(), any(), argThat(map -> 
                    map.containsKey("param1") && map.containsKey("param2")
            ), anyString());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return error when configuration not found")
        void testExecuteHttpCall_ConfigNotFound() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/unknown?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            when(resourceClientLibrary.getResource(URI.create(httpCallUri), HttpCallsConfiguration.class))
                    .thenReturn(null);
            
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"HttpCalls configuration not found: " + httpCallUri + "\"}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertEquals("{\"error\": true, \"message\": \"HttpCalls configuration not found: " + httpCallUri + "\"}", result);
        }

        @Test
        @DisplayName("Should return error when no httpcalls in configuration")
        void testExecuteHttpCall_NoHttpCallsInConfig() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/empty?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            config.setHttpCalls(List.of()); // Empty list

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());
            when(jsonSerialization.serialize(anyMap()))
                    .thenAnswer(inv -> {
                        Map<String, Object> map = inv.getArgument(0);
                        if (map.containsKey("error")) {
                            return "{\"error\": true, \"message\": \"" + map.get("message") + "\"}";
                        }
                        return "{}";
                    });

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertTrue(result.contains("error"));
            assertTrue(result.contains("No httpcalls found"));
        }

        @Test
        @DisplayName("Should return error when resource loading throws ServiceException")
        void testExecuteHttpCall_ServiceException() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/failing?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenThrow(new ServiceException("Connection refused"));
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"Error loading configuration: Connection refused\"}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("Should return error when HTTP call execution fails")
        void testExecuteHttpCall_ExecutionFailure() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/failing?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            HttpCall httpCall = new HttpCall();
            httpCall.setName("Failing Call");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenThrow(new RuntimeException("Request timeout"));
            when(jsonSerialization.serialize(anyMap()))
                    .thenReturn("{\"error\": true, \"message\": \"Execution error: Request timeout\"}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertTrue(result.contains("error"));
        }
    }

    @Nested
    @DisplayName("Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("Should cache configuration after first load")
        void testConfigurationCaching() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/cached?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            HttpCall httpCall = new HttpCall();
            httpCall.setName("Cached Call");
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenReturn(Map.of("result", "cached"));
            when(jsonSerialization.serialize(any())).thenReturn("{\"result\": \"cached\"}");

            // Act - Call twice
            eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);
            eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert - resourceClientLibrary should be called only once (cached)
            verify(resourceClientLibrary, times(1)).getResource(any(), eq(HttpCallsConfiguration.class));
        }
    }

    @Nested
    @DisplayName("Empty/Null Input Tests")
    class EmptyInputTests {

        @Test
        @DisplayName("Should handle empty arguments map")
        void testEmptyArguments() throws Exception {
            // Arrange
            String conversationId = "conv-123";
            String httpCallUri = "eddi://ai.labs.httpcalls/noargs?version=1";
            Map<String, Object> arguments = Collections.emptyMap();

            HttpCallsConfiguration config = new HttpCallsConfiguration();
            config.setTargetServerUrl("http://localhost:8080");
            HttpCall httpCall = new HttpCall();
            config.setHttpCalls(List.of(httpCall));

            when(resourceClientLibrary.getResource(any(), eq(HttpCallsConfiguration.class)))
                    .thenReturn(config);
            when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId))
                    .thenReturn(new ConversationMemorySnapshot());
            when(httpCallExecutor.execute(any(), isNull(), anyMap(), anyString()))
                    .thenReturn(Map.of("success", true));
            when(jsonSerialization.serialize(any())).thenReturn("{\"success\": true}");

            // Act
            String result = eddiToolBridge.executeHttpCall(conversationId, httpCallUri, arguments);

            // Assert
            assertNotNull(result);
            assertEquals("{\"success\": true}", result);
        }
    }
}
