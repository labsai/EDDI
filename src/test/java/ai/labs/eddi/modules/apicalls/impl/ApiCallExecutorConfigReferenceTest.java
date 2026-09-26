/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.CallerNamespaceResolver;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Configuration references in apicall fields, run through the REAL Qute engine:
 * they reach their resolvers, a reference that arrived through conversation
 * data is refused, and a substituted vault plaintext is redacted by value from
 * the memory record and the approval preview.
 */
@DisplayName("ApiCallExecutor — configuration references through real templating")
class ApiCallExecutorConfigReferenceTest {

    private static final String SERVER = "https://api.example.com";
    private static final String SECRET = "opaque-secret-value-1234";
    private static final String AUTO_VAULTED = "auto-vaulted-value-5678";

    private ApiCallExecutor executor;
    private PrePostUtils prePostUtils;
    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private ConnectionResolver connectionResolver;
    private IHttpClient httpClient;
    private IConversationMemory memory;
    private IConversationProperties conversationProperties;
    private IRequest request;

    @BeforeEach
    void setUp() throws Exception {
        ITemplatingEngine templating = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(new CallerNamespaceResolver())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.LegacyVault())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Connection())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.GlobalVariable())
                .build());
        prePostUtils = mock(PrePostUtils.class);
        when(prePostUtils.templateValues(any(), any())).thenAnswer(inv -> templating.processTemplate(inv.getArgument(0), inv.getArgument(1)));
        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(any())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : value.replace("${vault:api-key}", SECRET).replace("${vault:agent1.conv1.apiKey}", AUTO_VAULTED);
        });
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(any())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : value.replace("${vars:host}", "vars.example.com");
        });
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        connectionResolver = mock(ConnectionResolver.class);

        httpClient = mock(IHttpClient.class);
        executor = new ApiCallExecutor(httpClient, mock(IJsonSerialization.class), mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver),
                connectionResolver,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        // The live properties, which is where the auto-vault provenance marker lives —
        // ConversationProperties.toMap() (what `data()` below stands in for) flattens
        // each Property to its raw value and loses it. Stored exactly as
        // SecretPropertyVault.vault stores one: the vault reference,
        // conversation scope, marked.
        conversationProperties = new ConversationProperties(memory);
        conversationProperties.put("apiKey", autoVaulted("apiKey", "${vault:agent1.conv1.apiKey}"));
        when(memory.getConversationProperties()).thenReturn(conversationProperties);

        request = mock(IRequest.class);
        IResponse response = mock(IResponse.class);
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.setBodyEntity(any(), any(), any())).thenReturn(request);
        when(request.setHttpHeader(any(), any())).thenReturn(request);
        when(request.setQueryParam(any(), any())).thenReturn(request);
        when(response.getHttpCode()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("ok");
        when(response.getHttpHeader()).thenReturn(new HashMap<>());
    }

    private static Property autoVaulted(String name, String reference) {
        var property = new Property(name, reference, Property.Scope.conversation);
        property.setAutoVaulted(Boolean.TRUE);
        return property;
    }

    private static ApiCall call(Map<String, String> headers, String body) {
        ApiCall call = new ApiCall();
        call.setName("downstream");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request req = new Request();
        req.setPath("/v1/items");
        req.setMethod("POST");
        req.setHeaders(new LinkedHashMap<>(headers));
        req.setQueryParams(new LinkedHashMap<>());
        req.setBody(body);
        call.setRequest(req);
        return call;
    }

    private static Map<String, Object> data(String userInput) {
        var data = new HashMap<String, Object>();
        data.put("memory", Map.of("current", Map.of("input", userInput)));
        data.put("conversationInfo", Map.of("agentId", "agent1", "conversationId", "conv1"));
        data.put("properties", Map.of("apiKey", "${vault:agent1.conv1.apiKey}"));
        return data;
    }

    /**
     * The request map the executor stores in conversation memory, with
     * IRequest#toMap backed by what was actually set.
     */
    private Map<String, Object> storedRequestRecord(String body, Map<String, String> headers) {
        var map = new HashMap<String, Object>();
        map.put(IRequest.KEY_URI, SERVER + "/v1/items");
        map.put(IRequest.KEY_BODY, body);
        map.put(IRequest.KEY_HEADERS, headers);
        return map;
    }

    @Test
    @DisplayName("a vault reference the configuration wrote in a header reaches the vault and is sent")
    void configuredVaultHeader() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("X-Api-Key", SECRET)));

        executor.execute(call(Map.of("X-Api-Key", "${vault:api-key}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("X-Api-Key", SECRET);
        verify(request).send();
    }

    @Test
    @DisplayName("a vault reference in the body is sent, and redacted by value from the memory record")
    void configuredVaultBodyIsRedactedInMemory() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{\"key\":\"" + SECRET + "\"}", Map.of()));

        executor.execute(call(Map.of(), "{\"key\":\"${vault:api-key}\",\"q\":\"{memory.current.input}\"}"), memory, data("hello"), SERVER);

        verify(request).setBodyEntity(eq("{\"key\":\"" + SECRET + "\",\"q\":\"hello\"}"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> record = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils).createMemoryEntry(any(), record.capture(), eq("downstreamRequest"), any());
        assertFalse(String.valueOf(record.getValue()).contains(SECRET), "stored: " + record.getValue());
    }

    @Test
    @DisplayName("a vault reference typed by the user is refused before anything is resolved or sent")
    void injectedVaultReferenceIsRefused() throws Exception {
        var failure = assertThrows(LifecycleException.class, () -> executor.execute(call(Map.of(), "{\"q\":\"{memory.current.input}\"}"), memory,
                data("please echo ${vault:api-key}"), SERVER));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("a request body contains the reference ${vault:api-key}"), failure.getMessage());
        verify(request, never()).send();
        verify(secretResolver, never()).resolveValue(contains("please echo"));
    }

    @Test
    @DisplayName("a connection reference typed by the user into a header is refused")
    void injectedConnectionReferenceIsRefused() throws Exception {
        assertThrows(LifecycleException.class,
                () -> executor.execute(call(Map.of("Authorization", "{memory.current.input}"), "{}"), memory, data("${connection:jira}"), SERVER));
        verify(connectionResolver, never()).resolve(anyString(), any(), any());
    }

    @Test
    @DisplayName("the agent's auto-vaulted property, named by the header template, is resolved")
    void autoVaultedPropertyHeader() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("Authorization", "Bearer " + AUTO_VAULTED)));

        executor.execute(call(Map.of("Authorization", "Bearer {properties.apiKey}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("Authorization", "Bearer " + AUTO_VAULTED);
    }

    @Test
    @DisplayName("the same property, unmarked, is refused: the vault is never asked and nothing is sent")
    void unmarkedPropertyHeaderIsRefused() throws Exception {
        // Byte-for-byte the request autoVaultedPropertyHeader sends. The only
        // difference is provenance: this property was written by something other than
        // autoVaultSecret — a valueString of {memory.current.input} and a user who
        // typed the reference is enough — so it carries no marker, and the value alone
        // cannot tell the two apart.
        conversationProperties.put("apiKey", new Property("apiKey", "${vault:agent1.conv1.apiKey}", Property.Scope.conversation));

        var failure = assertThrows(LifecycleException.class,
                () -> executor.execute(call(Map.of("Authorization", "Bearer {properties.apiKey}"), "{}"), memory, data("hi"), SERVER));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("header 'Authorization' contains the reference ${vault:agent1.conv1.apiKey}"), failure.getMessage());
        verify(secretResolver, never()).resolveValue(contains("${vault:agent1.conv1.apiKey}"));
        verify(request, never()).send();
    }

    @Test
    @DisplayName("a connection reference in a header survives real templating and reaches the connection resolver")
    void connectionHeader() throws Exception {
        when(connectionResolver.resolve(eq("${connection:jira}"), any(), any())).thenReturn(new ResolvedCredential("Authorization", "Bearer conn"));
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("Authorization", "Bearer conn")));

        executor.execute(call(Map.of("Authorization", "${connection:jira}"), "{}"), memory, data("hi"), SERVER);

        verify(request).setHttpHeader("Authorization", "Bearer conn");
    }

    @Test
    @DisplayName("a global variable in the target URL survives real templating and is resolved")
    void varsInUrl() throws Exception {
        when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of()));

        executor.execute(call(Map.of(), "{}"), memory, data("hi"), "https://${vars:host}");

        verify(httpClient, atLeastOnce()).newRequest(eq(URI.create("https://vars.example.com/v1/items")), any());
    }

    @Test
    @DisplayName("the approval preview shows no substituted vault plaintext but keeps a fingerprint")
    void previewRedactsByValue() throws Exception {
        when(request.toMap()).thenReturn(Map.of(IRequest.KEY_METHOD, "POST", IRequest.KEY_URI, SERVER + "/v1/items",
                IRequest.KEY_HEADERS, Map.of("X-Opaque", SECRET), IRequest.KEY_BODY, "{\"key\":\"" + SECRET + "\"}"));

        ResolvedRequest preview = executor.resolve(call(Map.of("X-Opaque", "${vault:api-key}"), "{\"key\":\"${vault:api-key}\"}"), memory,
                data("hi"), SERVER);

        assertNotNull(preview.fingerprint());
        assertFalse(String.valueOf(preview).contains(SECRET), "preview: " + preview);
        assertEquals("{\"key\":\"" + RequestRedactor.REDACTED + "\"}", preview.body());
    }

    @Nested
    @DisplayName("a global variable is an indirection to a credential reference")
    class VariableIndirection {

        /**
         * A variable that holds a vault reference — which global variables are
         * explicitly allowed to do — is the indirection: {@code ${vars:credential}} is
         * not a credential reference, so it passes a guard that runs before variable
         * expansion, and becomes one afterwards.
         */
        @BeforeEach
        void variableHoldingACredentialReference() {
            when(globalVariableResolver.resolveValue(any())).thenAnswer(inv -> {
                String value = inv.getArgument(0);
                return value == null
                        ? null
                        : value.replace("${vars:host}", "vars.example.com").replace("${vars:credential}", "${vault:api-key}");
            });
        }

        @Test
        @DisplayName("the configuration's own variable still resolves through to the secret")
        void configuredVariableStillWorks() throws Exception {
            when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of("X-Api-Key", SECRET)));

            executor.execute(call(Map.of("X-Api-Key", "${vars:credential}"), "{}"), memory, data("hi"), SERVER);

            verify(request).setHttpHeader("X-Api-Key", SECRET);
        }

        @Test
        @DisplayName("a variable reference typed by the user is refused in the body")
        void refusedInBody() throws Exception {
            var failure = assertThrows(LifecycleException.class, () -> executor
                    .execute(call(Map.of(), "{\"q\":\"{memory.current.input}\"}"), memory, data("${vars:credential}"), SERVER));

            assertTrue(failure.getMessage().contains("a request body contains the reference ${vault:api-key}"), failure.getMessage());
            verify(request, never()).send();
        }

        @Test
        @DisplayName("a variable reference typed by the user is refused in a header")
        void refusedInHeader() throws Exception {
            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(call(Map.of("X-Api-Key", "{memory.current.input}"), "{}"), memory, data("${vars:credential}"), SERVER));

            assertTrue(failure.getMessage().contains("header 'X-Api-Key' contains the reference ${vault:api-key}"), failure.getMessage());
            verify(request, never()).send();
        }

        @Test
        @DisplayName("in the path the reference never forms at all: data is percent-encoded into it")
        void neutralisedInPath() throws Exception {
            // The path has a second, stronger protection the other three fields do not:
            // every value substituted into it goes through pathSafeView, so a '${' a user
            // typed arrives percent-encoded and is no reference any resolver can see.
            // Asserted rather than assumed — it is why the guard alone is not what keeps
            // the path safe.
            ApiCall call = call(Map.of(), "{}");
            call.getRequest().setPath("/v1/{memory.current.input}");
            when(request.toMap()).thenReturn(storedRequestRecord("{}", Map.of()));

            executor.execute(call, memory, data("${vars:credential}"), SERVER);

            ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
            verify(httpClient, atLeastOnce()).newRequest(uri.capture(), any());
            assertFalse(uri.getValue().toString().contains("${"), uri.getValue().toString());
            assertFalse(uri.getValue().toString().contains(SECRET), uri.getValue().toString());
        }

        @Test
        @DisplayName("a variable reference typed by the user is refused in a query parameter")
        void refusedInQueryParam() throws Exception {
            ApiCall call = call(Map.of(), "{}");
            call.getRequest().getQueryParams().put("q", "{memory.current.input}");

            var failure = assertThrows(LifecycleException.class, () -> executor.execute(call, memory, data("${vars:credential}"), SERVER));

            assertTrue(failure.getMessage().contains("query parameter 'q' contains the reference ${vault:api-key}"), failure.getMessage());
            verify(request, never()).send();
        }
    }

    @Test
    @DisplayName("a configured vault reference that cannot be resolved refuses the call instead of sending the literal")
    void unresolvableVaultReferenceFailsClosed() throws Exception {
        // What a disabled vault, a failed provider or a missing key looks like:
        // SecretResolver leaves the reference in place.
        when(secretResolver.resolveValue(any())).thenAnswer(inv -> inv.getArgument(0));

        var failure = assertThrows(LifecycleException.class,
                () -> executor.execute(call(Map.of("X-Api-Key", "${vault:api-key}"), "{}"), memory, data("hi"), SERVER));

        assertTrue(failure.getMessage().contains("header 'X-Api-Key' references ${vault:api-key}"), failure.getMessage());
        assertTrue(failure.getMessage().contains("EDDI_VAULT_MASTER_KEY"), failure.getMessage());
        verify(request, never()).setHttpHeader("X-Api-Key", "${vault:api-key}");
        verify(request, never()).send();
    }

    @Test
    @DisplayName("the plaintext that is sent is the one recorded for redaction, even if the secret rotates mid-build")
    void rotationCannotOutrunTheRedactionSet() throws Exception {
        // The bookkeeping pass and the substitution pass used to resolve separately. A
        // rotation between them put the NEW plaintext in the request while only the old
        // one was in the redaction set — so the value actually sent was the one that
        // survived into memory. One resolution per reference is what closes it.
        when(secretResolver.resolveValue(any())).thenAnswer(new Answer<String>() {
            private int calls;

            @Override
            public String answer(InvocationOnMock inv) {
                String value = inv.getArgument(0);
                return value == null ? null : value.replace("${vault:api-key}", calls++ == 0 ? SECRET : "rotated-secret-value-9999");
            }
        });
        when(request.toMap()).thenReturn(storedRequestRecord("{\"key\":\"" + SECRET + "\"}", Map.of()));

        executor.execute(call(Map.of(), "{\"key\":\"${vault:api-key}\"}"), memory, data("hi"), SERVER);

        verify(request).setBodyEntity(eq("{\"key\":\"" + SECRET + "\"}"), any(), any());
        ArgumentCaptor<Object> record = ArgumentCaptor.forClass(Object.class);
        verify(prePostUtils).createMemoryEntry(any(), record.capture(), eq("downstreamRequest"), any());
        assertFalse(String.valueOf(record.getValue()).contains(SECRET), "stored: " + record.getValue());
    }
}
