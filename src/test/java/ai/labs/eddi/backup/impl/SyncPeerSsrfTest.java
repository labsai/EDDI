/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.impl.SourceUrlValidator.SyncSourcePolicy;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.outbound.JdkClientEngine;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Security review 2026-10-17, M8: the sync source was validated once and then
 * connected to with no SSRF check, so a rebinding DNS name could pass as public
 * and then resolve to an internal address that received the forwarded bearer.
 * Every request now carries a policy the facade re-checks right before sending.
 */
@DisplayName("Instance sync — every request to the source is re-checked before it is sent")
class SyncPeerSsrfTest {

    @Test
    @DisplayName("the strict policy for a deployment that refuses private targets, metadata-only when it allows them")
    void connectPolicy() {
        assertEquals(SsrfPolicy.STRICT, SourceUrlValidator.connectPolicy("https://source.example.com", SyncSourcePolicy.STRICT));
        assertEquals(SsrfPolicy.STRICT, SourceUrlValidator.connectPolicy("https://source.example.com", null));
        assertEquals(SsrfPolicy.METADATA_ONLY,
                SourceUrlValidator.connectPolicy("http://eddi-staging:7070", new SyncSourcePolicy(false, true, Set.of())));
        var allowListed = new SyncSourcePolicy(true, false, Set.of("http://eddi-staging:7070"));
        assertEquals(SsrfPolicy.METADATA_ONLY, SourceUrlValidator.connectPolicy("http://eddi-staging:7070/", allowListed));
        assertEquals(SsrfPolicy.STRICT, SourceUrlValidator.connectPolicy("http://other:7070", allowListed));
        assertEquals(SsrfPolicy.STRICT, SourceUrlValidator.connectPolicy("not a url", allowListed));
    }

    @Test
    @DisplayName("under the strict policy a request that would land on an internal address is never sent")
    void strictRequestToAnInternalAddressIsNotSent() throws Exception {
        HttpClient client = mock(HttpClient.class);

        assertThrows(RemoteApiResourceSource.RemoteReadException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), "http://127.0.0.1:7070",
                        "Bearer source-token", mock(IJsonSerialization.class), SsrfPolicy.STRICT));
        // the facade's refusal is an IllegalArgumentException, which the export
        // rethrows as is
        assertThrows(IllegalArgumentException.class,
                () -> RemoteApiResourceSource.exportAgentArchive(JdkClientEngine.facade(client), "http://10.0.0.5", "agent", 1,
                        "Bearer source-token", SsrfPolicy.STRICT));

        verify(client, never()).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("allowing private targets still refuses the cloud metadata service")
    void metadataStillRefusedWhenPrivateTargetsAreAllowed() throws Exception {
        HttpClient client = mock(HttpClient.class);

        assertThrows(RemoteApiResourceSource.RemoteReadException.class,
                () -> RemoteApiResourceSource.listRemoteAgentDescriptors(JdkClientEngine.facade(client), "http://169.254.169.254",
                        "Bearer source-token", mock(IJsonSerialization.class), SsrfPolicy.METADATA_ONLY));

        verify(client, never()).send(any(), any(HttpResponse.BodyHandler.class));
    }
}
