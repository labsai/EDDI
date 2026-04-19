package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutionServiceTest {

    private ToolExecutionService service;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        service = new ToolExecutionService();
        cacheService = mock(ToolCacheService.class);
        rateLimiter = mock(ToolRateLimiter.class);
        costTracker = mock(ToolCostTracker.class);
        meterRegistry = new SimpleMeterRegistry();

        // Inject mocks via reflection (field injection)
        setField(service, "cacheService", cacheService);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", meterRegistry);
        setField(service, "jsonSerialization", mock(IJsonSerialization.class));

        service.init();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== executeToolWrapped ====================

    @Test
    void executeToolWrapped_success() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
        when(cacheService.get("testTool", "args")).thenReturn(null);

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "tool result",
                true, true, true, 60);

        assertEquals("tool result", result);
        verify(cacheService).put("testTool", "args", "tool result");
        verify(costTracker).trackToolCall("testTool", "conv-1");
    }

    @Test
    void executeToolWrapped_cachedResult() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
        when(cacheService.get("testTool", "args")).thenReturn("cached");

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "should not be called",
                true, true, true, 60);

        assertEquals("cached", result);
        verify(cacheService, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    void executeToolWrapped_rateLimited() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(false);

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "should not run",
                true, true, true, 60);

        assertTrue(result.contains("Rate limit exceeded"));
        verify(cacheService, never()).get(anyString(), anyString());
    }

    @Test
    void executeToolWrapped_rateLimitingDisabled() {
        when(cacheService.get("testTool", "args")).thenReturn(null);

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "result",
                false, true, true, 60);

        assertEquals("result", result);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
    }

    @Test
    void executeToolWrapped_cachingDisabled() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "result",
                true, false, true, 60);

        assertEquals("result", result);
        verify(cacheService, never()).get(anyString(), anyString());
        verify(cacheService, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    void executeToolWrapped_costTrackingDisabled() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
        when(cacheService.get("testTool", "args")).thenReturn(null);

        service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "result",
                true, true, false, 60);

        verify(costTracker, never()).trackToolCall(anyString(), anyString());
    }

    @Test
    void executeToolWrapped_nullConversationId_skipsCostTracking() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
        when(cacheService.get("testTool", "args")).thenReturn(null);

        service.executeToolWrapped(
                "testTool", "args", null,
                () -> "result",
                true, true, true, 60);

        verify(costTracker, never()).trackToolCall(anyString(), anyString());
    }

    @Test
    void executeToolWrapped_toolThrowsException() {
        when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
        when(cacheService.get("testTool", "args")).thenReturn(null);

        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> {
                    throw new RuntimeException("tool failed");
                },
                true, true, true, 60);

        assertTrue(result.contains("Error executing tool"));
        assertTrue(result.contains("tool failed"));
    }

    @Test
    void executeToolWrapped_allFeaturesDisabled() {
        var result = service.executeToolWrapped(
                "testTool", "args", "conv-1",
                () -> "plain result",
                false, false, false, 60);

        assertEquals("plain result", result);
    }

    // ==================== getCostTracker ====================

    @Test
    void getCostTracker_returnsInjected() {
        assertSame(costTracker, service.getCostTracker());
    }

    // ==================== executeToolsParallel ====================

    @Test
    void executeToolsParallel_mismatchedArrays_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.executeToolsParallel(
                        new Object[1], new java.lang.reflect.Method[2], new Object[1][],
                        "conv-1", null));
    }
}
