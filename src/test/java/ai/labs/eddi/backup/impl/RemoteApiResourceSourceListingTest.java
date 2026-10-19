/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.httpclient.outbound.JdkClientEngine;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The static agent listing behind the "connect to a remote instance" screen.
 * <p>
 * The listing goes through the real {@code OutboundHttp} facade; only the wire
 * is a mocked JDK client, reached through {@link JdkClientEngine}. The
 * guarantees are that the listing is one unpaged, authenticated call, and that
 * a listing which did not come back 200 is never mistaken for an empty
 * deployment.
 * <p>
 * This class used to test the HTTP clients a source built and closed itself.
 * Sources no longer own a client: every one shares the application's facade.
 */
@DisplayName("RemoteApiResourceSource — the agent listing")
class RemoteApiResourceSourceListingTest {

    private static final String BASE_URL = "https://203.0.113.10";

    private IJsonSerialization jsonSerialization;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        jsonSerialization = mock(IJsonSerialization.class);
        client = mock(HttpClient.class);
    }

    @AfterEach
    void tearDown() {
        // A test that asserts the interrupt flag must not hand it to the next one.
        Thread.interrupted();
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

        List<DocumentDescriptor> descriptors = RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), BASE_URL + "/",
                "Bearer source-token", jsonSerialization);

        assertEquals(List.of(descriptor), descriptors);

        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create(BASE_URL + "/agentstore/agents/descriptors?index=0&limit=0"),
                sent.getValue().uri());
        assertEquals("Bearer source-token", sent.getValue().headers().firstValue("Authorization").orElse(null));
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

        assertTrue(RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), BASE_URL, null, jsonSerialization).isEmpty());
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
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), BASE_URL, "Bearer stale",
                        jsonSerialization));

        assertTrue(thrown.getMessage().contains("401"),
                "the operator needs the status to tell a stale token from a wrong URL, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(BASE_URL), thrown.getMessage());
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
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(interrupted);

        var thrown = assertThrows(RuntimeException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), BASE_URL, null, jsonSerialization));

        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt flag must be restored before unwinding");
        assertTrue(thrown.getMessage().contains("Interrupted"), thrown.getMessage());
        assertSame(interrupted, thrown.getCause());
    }

    /** A base URL that is not addressable is refused before anything is sent. */
    @Test
    @DisplayName("a non-HTTP base URL is refused before anything is sent")
    void nonHttpBaseUrlIsRefusedBeforeSending() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), "ftp://203.0.113.10", null,
                        jsonSerialization));

        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertFalse(Thread.currentThread().isInterrupted(), "a leaked interrupt flag would corrupt this test");
    }
}
