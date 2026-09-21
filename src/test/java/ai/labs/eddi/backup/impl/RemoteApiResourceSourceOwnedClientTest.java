/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two paths that build their own {@link HttpClient} rather than being
 * handed one: the public constructor a sync endpoint uses, and the static agent
 * listing behind the "connect to a remote instance" screen.
 * <p>
 * Neither can be reached with a real client here — {@code HttpClient.build()}
 * opens a selector, which needs a loopback socket a sandboxed build does not
 * have — so {@code HttpClient.newBuilder()} is stubbed and the assertions are
 * on what the code asks the builder and the client for. That is the right level
 * anyway: the guarantees are that a client this class owns is configured (and
 * closed) by this class, and that a listing which did not come back 200 is
 * never mistaken for an empty deployment.
 */
@DisplayName("RemoteApiResourceSource — the clients it builds itself")
class RemoteApiResourceSourceOwnedClientTest {

    private static final String BASE_URL = "https://203.0.113.10";
    private static final String AGENT_ID = "aabbccddeeff112233445566";

    private IJsonSerialization jsonSerialization;
    private HttpClient.Builder builder;
    private HttpClient builtClient;
    private MockedStatic<HttpClient> httpClientStatics;

    @BeforeEach
    void setUp() {
        jsonSerialization = mock(IJsonSerialization.class);
        builder = mock(HttpClient.Builder.class, RETURNS_SELF);
        builtClient = mock(HttpClient.class);
        when(builder.build()).thenReturn(builtClient);
        httpClientStatics = mockStatic(HttpClient.class);
        httpClientStatics.when(HttpClient::newBuilder).thenReturn(builder);
    }

    @AfterEach
    void tearDown() {
        httpClientStatics.close();
        // A test that asserts the interrupt flag must not hand it to the next one.
        Thread.interrupted();
    }

    /**
     * A source built through the public constructor owns its client, so it applies
     * the class's own policy to it — never following a redirect, because a hostile
     * remote instance answering 3xx must not be able to bounce the caller's bearer
     * token at an address of its choosing.
     * <p>
     * The connect timeout is asserted as "set", not as a number: the policy this
     * test names is the redirect one, and the duration is a tunable an operator may
     * want changed without a test standing in the way. Dropping it entirely — and
     * letting a build hang on an unreachable host — still fails here.
     */
    @Test
    @DisplayName("the public constructor configures the client it builds")
    void publicConstructorConfiguresItsOwnClient() {
        new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, "Bearer t", jsonSerialization);

        verify(builder).followRedirects(HttpClient.Redirect.NEVER);
        verify(builder).connectTimeout(any(Duration.class));
    }

    /**
     * ...and it closes that client again. Callers already wrap every source in
     * try-with-resources; before this override they inherited a no-op, so a batch
     * sync over N agents left N clients — each with a selector thread and an
     * executor — alive until GC.
     */
    @Test
    @DisplayName("closing a source closes the client it built")
    void closingASourceClosesTheClientItBuilt() {
        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization);

        source.close();

        verify(builtClient).close();
    }

    /**
     * The mirror image, and the reason ownership is tracked at all: a client handed
     * in by the caller belongs to the caller. Closing it would take down a client
     * the caller is still using — for the very next agent of the same batch, say.
     */
    @Test
    @DisplayName("closing a source never closes a client it was handed")
    void closingASourceLeavesABorrowedClientAlone() {
        var borrowed = mock(HttpClient.class);
        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization, borrowed);

        source.close();

        verify(borrowed, never()).close();
    }

    /**
     * The agent listing: it asks the remote instance for every agent descriptor in
     * one unpaged call, sends the caller's own bearer token, and hands back what
     * the remote deserialized to. The URL and the {@code limit=0} matter — a paged
     * listing would show the operator a truncated agent list and let them "not
     * find" the agent they meant to sync.
     */
    @Test
    @DisplayName("the agent listing asks for every descriptor in one unpaged, authenticated call")
    void listRemoteAgentDescriptorsReadsTheWholeListing() throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Support Bot");
        stubResponse(200, "[{\"name\":\"Support Bot\"}]");
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor[].class)))
                .thenReturn(new DocumentDescriptor[]{descriptor});

        List<DocumentDescriptor> descriptors = RemoteApiResourceSource.listRemoteAgentDescriptors(
                BASE_URL + "/", "Bearer source-token", jsonSerialization);

        assertEquals(List.of(descriptor), descriptors);

        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(builtClient).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create(BASE_URL + "/agentstore/agents/descriptors?index=0&limit=0"),
                sent.getValue().uri());
        assertEquals("Bearer source-token", sent.getValue().headers().firstValue("Authorization").orElse(null));
        // The client is this method's own, so this method has to close it.
        verify(builtClient).close();
    }

    /**
     * A remote instance that deserializes to nothing is an empty list, not a null
     * the caller has to guard.
     */
    @Test
    @DisplayName("a listing that deserializes to null is an empty list")
    void nullDeserializationIsAnEmptyList() throws Exception {
        stubResponse(200, "null");
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor[].class))).thenReturn(null);

        assertTrue(RemoteApiResourceSource.listRemoteAgentDescriptors(BASE_URL, null, jsonSerialization).isEmpty());
    }

    /**
     * A status other than 200 is a failure, never an empty deployment — the whole
     * point of the screen this feeds is telling "that instance has no agents" apart
     * from "that instance would not talk to us", and the status has to be in the
     * message for the operator to tell an expired token from a wrong URL.
     */
    @Test
    @DisplayName("a non-200 listing fails and names the status, rather than reading as no agents")
    void nonOkListingFails() throws Exception {
        stubResponse(401, "");

        var thrown = assertThrows(RuntimeException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(BASE_URL, "Bearer stale", jsonSerialization));

        assertTrue(thrown.getMessage().contains("401"),
                "the operator needs the status to tell a stale token from a wrong URL, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(BASE_URL), thrown.getMessage());
        verify(builtClient).close();
    }

    /**
     * An interrupt is never swallowed. The caller — or the container shutting down
     * — asked this thread to stop, and a lost flag means the next blocking call
     * simply carries on, which for a batch sync means going on hammering a remote
     * instance nobody is waiting for any more.
     */
    @Test
    @DisplayName("an interrupted listing restores the flag and says it was interrupted")
    void interruptedListingRestoresTheFlag() throws Exception {
        var interrupted = new InterruptedException("shutting down");
        when(builtClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(interrupted);

        var thrown = assertThrows(RuntimeException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(BASE_URL, null, jsonSerialization));

        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt flag must be restored before unwinding");
        assertTrue(thrown.getMessage().contains("Interrupted"), thrown.getMessage());
        assertSame(interrupted, thrown.getCause());
    }

    /**
     * A base URL that is not addressable is rejected before any client is built —
     * building one and then throwing would leak a selector thread per bad request.
     */
    @Test
    @DisplayName("a non-HTTP base URL is refused before a client is built")
    void nonHttpBaseUrlIsRefusedBeforeBuilding() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors("ftp://203.0.113.10", null, jsonSerialization));

        verify(builder, never()).build();
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(builtClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertFalse(Thread.currentThread().isInterrupted(), "a leaked interrupt flag would corrupt this test");
    }
}
