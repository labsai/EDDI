/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code ${connection:…}} half of {@code ApiCallExecutor#buildRequest}.
 * <p>
 * Every other {@code ApiCallExecutor} test in this package constructs the
 * executor with a {@code null} {@link ConnectionResolver}, so the whole
 * connection branch — resolution, the header-name agreement check and the
 * case-insensitive collision guard — never executed under test, and deleting
 * any of those call sites left the suite green. These tests wire a resolver in
 * and assert on what actually reaches {@link IRequest}.
 */
@DisplayName("ApiCallExecutor — ${connection:…} references in outbound requests")
class ApiCallExecutorConnectionHeaderTest {

    private static final String SERVER = "http://api.example.com";
    private static final URI TARGET = URI.create("http://api.example.com/api/test");
    private static final String JIRA_REF = "${connection:jira}";
    private static final ResolvedCredential JIRA_CREDENTIAL = new ResolvedCredential("Authorization", "Bearer live-token-42");

    private ConnectionResolver connectionResolver;
    private ApiCallExecutor executor;

    private IConversationMemory memory;
    private IRequest mockRequest;

    @BeforeEach
    void setUp() throws Exception {
        IHttpClient httpClient = mock(IHttpClient.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        IRuntime runtime = mock(IRuntime.class);
        PrePostUtils prePostUtils = mock(PrePostUtils.class);
        connectionResolver = mock(ConnectionResolver.class);

        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        CallerIdentityContext callerIdentityContext = mock(CallerIdentityContext.class);
        // Pass-through: these tests carry no ${vault:…}, ${vars:…} or ${caller:…}
        // references, so every earlier resolution stage must hand the value on
        // untouched — otherwise a failure could not be attributed to the connection
        // branch under test.
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        executor = new ApiCallExecutor(httpClient, jsonSerialization, runtime, prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, callerIdentityContext, new RequestRedactor(callerIdentityResolver), connectionResolver,
                false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));

        mockRequest = mock(IRequest.class);
        IResponse mockResponse = mock(IResponse.class);
        when(mockRequest.toMap()).thenReturn(new HashMap<>());
        when(httpClient.newRequest(any(URI.class), any())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(mockResponse);
        when(mockRequest.setBodyEntity(any(), any(), any())).thenReturn(mockRequest);
        when(mockRequest.setHttpHeader(any(), any())).thenReturn(mockRequest);
        when(mockRequest.setQueryParam(any(), any())).thenReturn(mockRequest);

        when(prePostUtils.executePreRequestPropertyInstructions(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(prePostUtils.templateValues(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        when(mockResponse.getHttpCode()).thenReturn(200);
        when(mockResponse.getContentAsString()).thenReturn("ok");
        when(mockResponse.getHttpHeader()).thenReturn(new HashMap<>());
    }

    @Nested
    @DisplayName("A sole reference is replaced by the resolved credential")
    class SoleReference {

        @Test
        @DisplayName("a header that is exactly one connection reference is sent as the resolved credential value")
        void soleReferenceBecomesCredential() throws Exception {
            givenConnectionResolvesToJiraCredential();

            executor.execute(callWithHeaders(Map.of("Authorization", JIRA_REF)), memory, templateData("alice"), SERVER);

            // Asserting on the WHOLE captured header map, not merely that the credential
            // was written: that positively rules out the raw ${connection:…} literal
            // also going out, which is what happens if the branch stops replacing.
            assertEquals(Map.of("Authorization", "Bearer live-token-42"), capturedHeaders(),
                    "the request must carry the resolved credential; sending the raw ${connection:…} literal means the "
                            + "provider sees a placeholder and answers 401 with nothing naming the cause");
        }

        @Test
        @DisplayName("the header is written under the connection's own header name, not the config's casing")
        void connectionOwnsTheHeaderName() throws Exception {
            // The connection knows whether its provider wants Authorization or
            // X-Api-Key; the config author only has to name it compatibly. Writing the
            // config's spelling instead makes the connection's declared name a lie the
            // moment anything downstream reads header names case-sensitively.
            givenConnectionResolvesToJiraCredential();

            executor.execute(callWithHeaders(Map.of("authorization", JIRA_REF)), memory, templateData("alice"), SERVER);

            assertEquals(Map.of("Authorization", "Bearer live-token-42"), capturedHeaders(),
                    "the connection's header name must win over the config's spelling, so what the connection declares "
                            + "and what is sent cannot disagree");
        }

        @Test
        @DisplayName("resolution is asked for this request's target URI and the conversation's own user id")
        void resolutionCarriesTargetAndPrincipal() throws Exception {
            // Both arguments are load-bearing one layer down: the target is what the
            // connection's baseUrlAllowlist is checked against, and the user id is the
            // cross-check that refuses when the call was built for one user while the
            // turn is running as another. Passing null for either silently disables a
            // guard without changing anything visible here.
            givenConnectionResolvesToJiraCredential();

            executor.execute(callWithHeaders(Map.of("Authorization", JIRA_REF)), memory, templateData("alice"), SERVER);

            verify(connectionResolver).resolve(eq(JIRA_REF), eq(TARGET), eq("alice"));
        }

        @Test
        @DisplayName("a header naming something other than the connection's header is refused before the call goes out")
        void headerNameDisagreesWithConnection() throws Exception {
            givenConnectionResolvesToJiraCredential();

            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(Map.of("X-Api-Key", JIRA_REF)), memory, templateData("alice"), SERVER),
                    "a config whose header name and connection header name disagree must fail loudly rather than send "
                            + "the credential under a name the config never mentions");
            assertInstanceOf(IllegalArgumentException.class, failure.getCause(),
                    "the refusal must be the configuration error, not some other failure that happens to abort the call");
            assertTrue(failure.getMessage().contains("Header 'X-Api-Key' references a connection whose header is 'Authorization'"),
                    "the message must name both sides so the author can fix it; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
        }
    }

    @Nested
    @DisplayName("A reference must stand alone in its header value")
    class SoleReferenceEnforcement {

        @Test
        @DisplayName("literal text around a reference is refused rather than silently dropped")
        void literalTextAroundReferenceRefused() throws Exception {
            // A connection supplies the WHOLE header value, so "Bearer ${connection:x}"
            // used to send the bare token with the author's scheme discarded — and the
            // 401 that came back named nothing.
            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(Map.of("Authorization", "Bearer " + JIRA_REF)), memory,
                            templateData("alice"), SERVER),
                    "mixing literal text with a connection reference must not send a half-built header");
            assertInstanceOf(IllegalArgumentException.class, failure.getCause(),
                    "the refusal must be the configuration error, not an unrelated failure earlier in buildRequest");
            assertTrue(failure.getMessage().contains("Header 'Authorization'"),
                    "the message must name the offending header; was: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("the literal text"),
                    "the message must say that surrounding literal text is the problem — the 'second reference' wording "
                            + "would misdescribe this config; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
            verify(connectionResolver, never()).resolve(anyString(), any(), any());
        }

        @Test
        @DisplayName("two references in one header value are refused rather than resolving only the first")
        void twoReferencesInOneValueRefused() throws Exception {
            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(Map.of("Authorization", JIRA_REF + "${connection:github}")), memory,
                            templateData("alice"), SERVER),
                    "only the first reference would ever be parsed, so the second must not be silently dropped");
            assertInstanceOf(IllegalArgumentException.class, failure.getCause(),
                    "the refusal must be the configuration error, not an unrelated failure earlier in buildRequest");
            assertTrue(failure.getMessage().contains("Header 'Authorization'") && failure.getMessage().contains("a second ${connection:"),
                    "the message must name the header and say a second reference is present; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
            verify(connectionResolver, never()).resolve(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("A connection-owned header name cannot collide with anything else")
    class HeaderCollision {

        @Test
        @DisplayName("a plain header written after a connection-owned one of the same name (differing case) is refused")
        void plainHeaderAfterConnectionHeader() throws Exception {
            // Vert.x header put REPLACES case-insensitively, so without the guard the
            // plain value would displace the credential — or not — purely by config
            // iteration order, and the request would go out either way.
            givenConnectionResolvesToJiraCredential();

            var headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", JIRA_REF);
            headers.put("authorization", "Basic hand-written");

            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(headers), memory, templateData("alice"), SERVER),
                    "one header name cannot be owned by a connection and set by hand at the same time");
            assertTrue(failure.getMessage().contains("is set both directly and by a connection"),
                    "the message must say the two writers collide; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
        }

        @Test
        @DisplayName("a connection-owned header written after a plain one of the same name (differing case) is refused")
        void connectionHeaderAfterPlainHeader() throws Exception {
            // The mirror image, and the one that used to slip through: the collision set
            // was only consulted inside the connection branch, so a plain header seen
            // FIRST left no claim for that branch to trip over.
            givenConnectionResolvesToJiraCredential();

            var headers = new LinkedHashMap<String, String>();
            headers.put("authorization", "Basic hand-written");
            headers.put("Authorization", JIRA_REF);

            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(headers), memory, templateData("alice"), SERVER),
                    "config order must not decide whether a hand-written header or a credential is sent");
            assertTrue(failure.getMessage().contains("is set both directly and by a connection"),
                    "the message must say the two writers collide; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
        }

        @Test
        @DisplayName("two connection references resolving to one header name are refused")
        void twoConnectionsClaimingOneHeader() throws Exception {
            // Both resolve to Authorization; one of the two credentials would be dropped
            // with no signal, and which one is iteration order again.
            givenConnectionResolvesToJiraCredential();

            var headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", JIRA_REF);
            headers.put("authorization", "${connection:github}");

            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(callWithHeaders(headers), memory, templateData("alice"), SERVER),
                    "two credentials cannot occupy one header");
            assertTrue(failure.getMessage().contains("More than one header resolves to 'Authorization'"),
                    "the message must distinguish this from the plain-header collision so the author knows which of the "
                            + "two references to remove; was: " + failure.getMessage());
            verify(mockRequest, never()).send();
        }

        @Test
        @DisplayName("positive control — a connection header and an unrelated plain header both arrive")
        void unrelatedHeadersBothArrive() throws Exception {
            // Without this, every collision test above would still pass had the guard
            // been over-tightened into "refuse whenever a connection is present".
            givenConnectionResolvesToJiraCredential();

            var headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", JIRA_REF);
            headers.put("Accept", "application/json");

            executor.execute(callWithHeaders(headers), memory, templateData("alice"), SERVER);

            assertEquals(Map.of("Authorization", "Bearer live-token-42", "Accept", "application/json"), capturedHeaders(),
                    "two genuinely different header names must both reach the request — the collision guard is about one "
                            + "name being claimed twice, not about connections being present at all");
        }

        @Test
        @DisplayName("positive control — two plain headers differing only in case still both pass through")
        void twoPlainHeadersDifferingOnlyInCaseStillPass() throws Exception {
            // No connection owns the name, so neither value is a credential and the
            // long-standing behaviour is deliberately preserved. Widening the guard to
            // every case-insensitive duplicate would reject working hand-authored
            // configs for no security gain.
            var headers = new LinkedHashMap<String, String>();
            headers.put("Accept", "application/json");
            headers.put("accept", "text/plain");

            executor.execute(callWithHeaders(headers), memory, templateData("alice"), SERVER);

            var names = ArgumentCaptor.forClass(String.class);
            verify(mockRequest, times(2)).setHttpHeader(names.capture(), anyString());
            assertEquals(List.of("Accept", "accept"), names.getAllValues(),
                    "both plain headers must still be written; the collision guard must not fire when no connection "
                            + "claims the name");
        }
    }

    @Nested
    @DisplayName("A connection reference is refused anywhere other than a header")
    class ReferenceOutsideHeaders {

        @Test
        @DisplayName("a reference in the request path is refused")
        void referenceInPath() throws Exception {
            ApiCall call = callWithHeaders(Map.of());
            call.getRequest().setPath("/api/" + JIRA_REF);

            assertRefusedOutsideHeader(call, "the request path");
        }

        @Test
        @DisplayName("a reference in a query parameter is refused")
        void referenceInQueryParam() throws Exception {
            ApiCall call = callWithHeaders(Map.of());
            call.getRequest().setQueryParams(new LinkedHashMap<>(Map.of("token", JIRA_REF)));

            assertRefusedOutsideHeader(call, "a query parameter");
        }

        @Test
        @DisplayName("a reference in the request body is refused")
        void referenceInBody() throws Exception {
            ApiCall call = callWithHeaders(Map.of());
            call.getRequest().setMethod("POST");
            call.getRequest().setBody("{\"key\":\"" + JIRA_REF + "\"}");

            assertRefusedOutsideHeader(call, "a request body");
        }

        private void assertRefusedOutsideHeader(ApiCall call, String expectedLocation) throws Exception {
            var failure = assertThrows(LifecycleException.class,
                    () -> executor.execute(call, memory, templateData("alice"), SERVER),
                    "a credential outside a header is recorded by every hop before the provider sees it, so it must "
                            + "never be built into the request");
            assertInstanceOf(IllegalArgumentException.class, failure.getCause(),
                    "the refusal must be the configuration error, not an incidental URI or template failure");
            assertTrue(failure.getMessage().contains("may only appear in a header, not in " + expectedLocation),
                    "the message must name where the reference was found so the author knows what to move; was: "
                            + failure.getMessage());
            verify(mockRequest, never()).send();
            verify(connectionResolver, never()).resolve(anyString(), any(), any());
        }
    }

    // --- helpers ---

    /**
     * {@code doReturn} rather than {@code when(...)}: the stubbed call's arguments
     * are matchers, and the executor invokes {@code resolve} with values built from
     * other mocks.
     */
    private void givenConnectionResolvesToJiraCredential() {
        doReturn(JIRA_CREDENTIAL).when(connectionResolver).resolve(anyString(), any(), any());
    }

    private static ApiCall callWithHeaders(Map<String, String> headers) {
        ApiCall call = new ApiCall();
        call.setName("connection-call");
        call.setSaveResponse(false);
        call.setResponseObjectName("response");
        call.setFireAndForget(false);
        Request request = new Request();
        request.setPath("/api/test");
        request.setMethod("GET");
        // LinkedHashMap: buildRequest walks the header map in iteration order, and the
        // collision tests are precisely about which of two writers is seen first.
        request.setHeaders(new LinkedHashMap<>(headers));
        call.setRequest(request);
        return call;
    }

    /** Template data shaped the way {@code MemoryItemConverter} produces it. */
    private static Map<String, Object> templateData(String userId) {
        var data = new HashMap<String, Object>();
        data.put("userInfo", new HashMap<String, Object>(Map.of("userId", userId)));
        return data;
    }

    private Map<String, String> capturedHeaders() {
        var names = ArgumentCaptor.forClass(String.class);
        var values = ArgumentCaptor.forClass(String.class);
        verify(mockRequest, atLeastOnce()).setHttpHeader(names.capture(), values.capture());
        var captured = new LinkedHashMap<String, String>();
        for (int i = 0; i < names.getAllValues().size(); i++) {
            captured.put(names.getAllValues().get(i), values.getAllValues().get(i));
        }
        return captured;
    }
}
