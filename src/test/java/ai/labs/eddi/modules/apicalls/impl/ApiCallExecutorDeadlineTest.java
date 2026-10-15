/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.shared.MutableTestClock;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Per-call timeout and {@code retryApiCallInstruction} inside a turn deadline.
 */
@DisplayName("ApiCallExecutor — turn deadline")
class ApiCallExecutorDeadlineTest {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest mockRequest;
    private IResponse mockResponse;
    private final MutableTestClock clock = new MutableTestClock();

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null,
                false, DEFAULT_TIMEOUT_MILLIS, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        mockRequest = mock(IRequest.class);
        mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);
        when(mockResponse.getHttpCode()).thenReturn(503);
        when(mockResponse.getContentAsString()).thenReturn("Service Unavailable");
        when(mockResponse.getHttpCodeMessage()).thenReturn("Service Unavailable");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private static ApiCall callRetrying503(int maxRetries, int backoffMs, Integer timeoutMs) {
        ApiCall call = new ApiCall();
        call.setName("test-call");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        call.setTimeoutInMillis(timeoutMs);
        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        call.setRequest(request);
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(maxRetries);
        retry.setRetryOnHttpCodes(List.of(503));
        retry.setExponentialBackoffDelayInMillis(backoffMs);
        var post = new HttpPostResponse();
        post.setRetryApiCallInstruction(retry);
        call.setPostResponse(post);
        return call;
    }

    private TurnDeadline deadline(long budgetMs, long reserveMs) {
        return TurnDeadline.of(clock, clock.millis(), budgetMs, reserveMs);
    }

    @Test
    @DisplayName("without a deadline every configured retry runs and the timeout is the configured one")
    void noDeadlineNoChange() throws Exception {
        executor.execute(callRetrying503(2, 0, null), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(3)).send();
        verify(mockRequest, atLeastOnce()).setTimeout(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        verify(mockRequest, never()).setTimeout(longThat(t -> t != DEFAULT_TIMEOUT_MILLIS), any());
    }

    @Test
    @DisplayName("retries that cannot fit in what is left of the turn are skipped; the first call is never skipped")
    void retriesAreSkippedWhenTheyDoNotFit() throws Exception {
        // 3000 ms left, 1000 reserved = 2000 < the 3000 ms a retry of this call needs.
        when(memory.getTurnDeadline()).thenReturn(deadline(3_000, 1_000));

        var result = executor.execute(callRetrying503(2, 0, null), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(1)).send();
        assertNotNull(result, "the failed first response is still returned");
    }

    @Test
    @DisplayName("with room to spare the retries run")
    void retriesRunWhenThereIsRoom() throws Exception {
        when(memory.getTurnDeadline()).thenReturn(deadline(60_000, 1_500));

        executor.execute(callRetrying503(2, 0, null), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(3)).send();
    }

    @Test
    @DisplayName("a retry backoff that would eat the remaining budget skips the retry")
    void backoffCountsAgainstTheBudget() throws Exception {
        // 8000 left - 1000 reserve = 7000; a 5000 ms backoff + 3000 ms attempt = 8000 >
        // 7000.
        when(memory.getTurnDeadline()).thenReturn(deadline(8_000, 1_000));

        executor.execute(callRetrying503(2, 5_000, null), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, times(1)).send();
    }

    @Test
    @DisplayName("the per-call timeout is shortened to what the turn can spare")
    void timeoutIsClampedToTheRemainingBudget() throws Exception {
        when(memory.getTurnDeadline()).thenReturn(deadline(5_000, 1_000));

        executor.execute(callRetrying503(0, 0, null), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest).setTimeout(4_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("a configured timeout shorter than the budget is kept")
    void shorterConfiguredTimeoutIsKept() throws Exception {
        when(memory.getTurnDeadline()).thenReturn(deadline(60_000, 1_500));

        executor.execute(callRetrying503(0, 0, 2_000), memory, new HashMap<>(), "http://example.com");

        verify(mockRequest, atLeastOnce()).setTimeout(2_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("an already expired deadline fails fast with a clear message and sends nothing")
    void expiredDeadlineFailsFast() throws Exception {
        var d = deadline(1_000, 500);
        clock.advance(1_000);
        when(memory.getTurnDeadline()).thenReturn(d);

        var ex = assertThrows(LifecycleException.class,
                () -> executor.execute(callRetrying503(2, 0, null), memory, new HashMap<>(), "http://example.com"));

        verify(mockRequest, never()).send();
        assertTrue(ex.getMessage().contains("turn deadline"), ex.getMessage());
    }

    @Test
    @DisplayName("deadlineBoundedTimeout: reserve is respected, and a sliver budget falls back to what is left")
    void boundedTimeoutHelper() {
        assertEquals(4_000, ApiCallExecutor.deadlineBoundedTimeout(30_000, deadline(5_000, 1_000)));
        assertEquals(2_000, ApiCallExecutor.deadlineBoundedTimeout(2_000, deadline(60_000, 1_500)));
        // 1200 left, 1000 reserved => 200 < 500: use all 1200 rather than 200
        assertEquals(1_200, ApiCallExecutor.deadlineBoundedTimeout(30_000, deadline(1_200, 1_000)));
    }
}
