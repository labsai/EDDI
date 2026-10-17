/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.outbound.ScriptedEngine;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The size cap on what a sync source may send, enforced by the outbound facade
 * itself — not simulated by a stub that throws the refusal.
 */
@DisplayName("RemoteApiResourceSource — the response size cap")
class RemoteApiResourceSourceSizeCapTest {

    private static final String BASE_URL = "https://203.0.113.10";
    private static final String AGENT_ID = "aabbccddeeff112233445566";

    @Test
    @DisplayName("a document declared larger than eddi.backup.sync.max-response-bytes is refused before it is read")
    void declaredOversizedDocumentIsRefused() {
        long overCap = RemoteApiResourceSource.DEFAULT_MAX_RESPONSE_BYTES + 1;
        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("an oversized body must not be read at all");
            }
        };
        ScriptedEngine engine = new ScriptedEngine()
                .respond(200, Map.of("Content-Length", List.of(String.valueOf(overCap))), neverRead);
        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, mock(IJsonSerialization.class),
                TestOutbound.facade(engine));

        var thrown = assertThrows(RuntimeException.class, source::readAgent);

        assertTrue(RemoteApiResourceSource.isTooLarge(thrown), "the refusal must be recognisable as the size cap: " + thrown);
        assertTrue(thrown.getMessage().contains(RemoteApiResourceSource.MAX_RESPONSE_BYTES_PROPERTY),
                "the operator must be told which property sets the cap: " + thrown.getMessage());
    }
}
