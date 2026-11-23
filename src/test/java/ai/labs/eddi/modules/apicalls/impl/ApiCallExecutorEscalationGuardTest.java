/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.mockito.InOrder;
import ai.labs.eddi.engine.hitl.tools.ApprovedCallScope;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The escalation guard (email plan v3 §5.3) on the {@code httpcalls} path: a
 * rule-driven or LLM-driven HTTP call of a turn a mail started never writes to
 * EDDI's channel store, whatever the call is named.
 */
@DisplayName("ApiCallExecutor — the email escalation guard")
class ApiCallExecutorEscalationGuardTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private IHttpClient httpClient;
    private ApiCallExecutor executor;
    private IConversationMemory memory;
    private IRequest request;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(IHttpClient.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        var prePostUtils = new PrePostUtils(jsonSerialization, new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));
        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        request = mock(IRequest.class);
        var response = mock(IResponse.class);
        when(request.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("{}");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null, true,
                Optional.of("graph.example.com"), 30_000L, 2_000_000);
    }

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    private static ApiCall call(String method, String path) {
        var call = new ApiCall();
        call.setName("innocentLookingName");
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        var request = new Request();
        request.setPath(path);
        request.setMethod(method);
        call.setRequest(request);
        return call;
    }

    @Test
    @DisplayName("a tainted POST to the channel store is refused before a request exists")
    void taintedWriteRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));

        assertThrows(Exception.class,
                () -> executor.execute(call("POST", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(),
                        "https://graph.example.com"));
        verify(httpClient, never()).newRequest(any(URI.class), any());
    }

    @Test
    @DisplayName("the same call is made from an untainted turn, and a tainted read is made too")
    void othersSent() throws Exception {
        executor.execute(call("POST", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(), "https://graph.example.com");
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        executor.execute(call("GET", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(), "https://graph.example.com");

        verify(httpClient, times(2)).newRequest(any(URI.class), any());
    }

    /**
     * The sending half of the start-path routes: a tainted turn's httpcall to
     * {@code POST /agents/{id}/start} or {@code /schedulestore/schedules} is made —
     * starting is not refused — and carries {@code X-Eddi-Turn-Origin: email}, set
     * after the configured headers, so the EDDI that receives it taints what it
     * starts.
     */
    @Test
    @DisplayName("a tainted turn's call to start a conversation or create a schedule is made, and carries X-Eddi-Turn-Origin")
    void taintedStartCarriesTheHeader() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        var start = call("POST", "/agents/aabbccddee1122334455aa01/start");
        start.getRequest().setHeaders(new HashMap<>(Map.of(TurnOrigin.HEADER, "none")));

        executor.execute(start, memory, new HashMap<>(), "https://graph.example.com");
        executor.execute(call("POST", "/schedulestore/schedules"), memory, new HashMap<>(), "https://graph.example.com");

        verify(httpClient, times(2)).newRequest(any(URI.class), any());
        InOrder order = inOrder(request);
        order.verify(request).setHttpHeader(TurnOrigin.HEADER, "none");
        order.verify(request).setHttpHeader(TurnOrigin.HEADER, TurnOrigin.EMAIL);
        verify(request, times(2)).setHttpHeader(TurnOrigin.HEADER, TurnOrigin.EMAIL);
    }

    @Test
    @DisplayName("an untainted turn's calls carry no X-Eddi-Turn-Origin")
    void untaintedCarriesNoHeader() throws Exception {
        executor.execute(call("POST", "/agents/aabbccddee1122334455aa01/start"), memory, new HashMap<>(), "https://graph.example.com");

        verify(request, never()).setHttpHeader(eq(TurnOrigin.HEADER), any());
    }

    /** As {@link #executor}, with ssrf-protection off: redirects are followed. */
    private ApiCallExecutor redirectFollowingExecutor() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        var prePostUtils = new PrePostUtils(jsonSerialization, new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));
        return new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null, false, 30_000L,
                2_000_000);
    }

    @Test
    @DisplayName("with ssrf-protection off, a tainted write follows no redirect; a tainted read and an untainted write do")
    void taintedWriteFollowsNoRedirect() throws Exception {
        ApiCallExecutor following = redirectFollowingExecutor();
        following.execute(call("POST", "/api/orders"), memory, new HashMap<>(), "https://graph.example.com");
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        following.execute(call("GET", "/api/orders"), memory, new HashMap<>(), "https://graph.example.com");
        verify(request, never()).setFollowRedirects(false);

        following.execute(call("POST", "/api/orders"), memory, new HashMap<>(), "https://graph.example.com");
        verify(request).setFollowRedirects(false);
    }

    @Test
    @DisplayName("a tainted configuration write is refused, unless it is the approved execution of a gated call")
    void taintedConfigWriteNeedsApproval() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));

        var refused = assertThrows(Exception.class, () -> executor.execute(call("PUT", "/llmstore/llms/aabbccddee1122334455aa01"), memory,
                new HashMap<>(), "https://graph.example.com"));
        assertTrue(String.valueOf(refused.getMessage()).contains("unless a person approves"), refused.getMessage());
        assertThrows(Exception.class, () -> executor.execute(call("PUT", "/langchainstore/langchains/aabbccddee1122334455aa01"), memory,
                new HashMap<>(), "https://graph.example.com"), "the v5 store name too");
        verify(httpClient, never()).newRequest(any(URI.class), any());

        ApprovedCallScope.run("agent-1", () -> {
            try {
                executor.execute(call("PUT", "/llmstore/llms/aabbccddee1122334455aa01"), memory, new HashMap<>(), "https://graph.example.com");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        verify(httpClient).newRequest(any(URI.class), any());
        verify(request).setHttpHeader(TurnOrigin.APPROVED_HEADER, "true");
    }

    /**
     * Request pinning for a gated configuration write of a mail-started turn: the
     * preview is built as the approved execution would build it, so the approval
     * binds to the request — the gate and the resume re-check, both outside the
     * approved scope, resolve the same fingerprint. Refusing the preview left the
     * call approved unpinned, on its tool name and arguments alone.
     */
    @Test
    @DisplayName("a tainted configuration write resolves to a pinnable request; the preview sends nothing and the guarded endpoints still refuse")
    void taintedConfigWritePreviewIsPinned() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        var configWrite = call("PATCH", "/llmstore/llms/aabbccddee1122334455aa01");

        var atGate = executor.resolve(configWrite, memory, new HashMap<>(), "https://graph.example.com");
        var atResume = executor.resolve(configWrite, memory, new HashMap<>(), "https://graph.example.com");

        assertNotNull(atGate.fingerprint(), "the approval must be pinned to the request");
        assertEquals(atGate.fingerprint(), atResume.fingerprint());
        verify(request, never()).send();
        verify(request, never()).setHttpHeader(TurnOrigin.APPROVED_HEADER, "true");

        assertThrows(Exception.class, () -> executor.resolve(call("POST", "/channelstore/channels/abc/email-senders"), memory, new HashMap<>(),
                "https://graph.example.com"), "a guarded endpoint is refused in a preview too");
        assertThrows(Exception.class, () -> executor.execute(configWrite, memory, new HashMap<>(), "https://graph.example.com"),
                "the execution still needs the approved scope");
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a configured X-Eddi-Approved-Call is replaced on a tainted call that nobody approved")
    void approvalHeaderCannotBeConfigured() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        var start = call("POST", "/agents/aabbccddee1122334455aa01/start");
        start.getRequest().setHeaders(new HashMap<>(Map.of(TurnOrigin.APPROVED_HEADER, "true")));

        executor.execute(start, memory, new HashMap<>(), "https://graph.example.com");

        InOrder order = inOrder(request);
        order.verify(request).setHttpHeader(TurnOrigin.APPROVED_HEADER, "true");
        order.verify(request).setHttpHeader(TurnOrigin.APPROVED_HEADER, "false");
    }
}
