/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.impl.RemoteApiResourceSource.RemoteReadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How a first promotion fetches the source's export archive.
 * <p>
 * This is the one place the sync hands the source instance a say over where the
 * caller's bearer token goes next, so what it does with the answer matters more
 * than the happy path: the archive is downloaded from the base URL the
 * validator approved, never from wherever the source's {@code Location} points,
 * and the size cap holds before the body is read.
 */
@DisplayName("RemoteApiResourceSource — fetching the source's export archive")
class RemoteArchiveFetchTest {

    private static final String BASE = "http://staging.internal:7070";
    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final byte[] ARCHIVE = "PK-archive-bytes".getBytes(StandardCharsets.UTF_8);

    private HttpClient client;
    private final List<HttpRequest> sent = new ArrayList<>();

    private int exportStatus;
    private String location;
    private int downloadStatus;
    private long declaredLength;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(HttpClient.class);
        exportStatus = 202;
        location = BASE + "/backup/export/agent-" + AGENT_ID + "-3.zip";
        downloadStatus = 200;
        declaredLength = ARCHIVE.length;

        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            sent.add(request);
            String path = request.uri().getPath();
            if ("POST".equals(request.method())) {
                return response(exportStatus, location == null ? Map.of() : Map.of("Location", List.of(location)), null);
            }
            if (path.endsWith("/agentstore/agents/descriptors")) {
                return response(200, Map.of(), "[{\"resource\":\"eddi://ai.labs.agent/agentstore/agents/"
                        + AGENT_ID + "?version=7\"}]");
            }
            return response(downloadStatus, Map.of("Content-Length", List.of(String.valueOf(declaredLength))),
                    new ByteArrayInputStream(ARCHIVE));
        }).when(client).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("exports the version asked for and returns the archive")
    void fetchesTheArchive() {
        byte[] body = RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, "Bearer t");

        assertArrayEquals(ARCHIVE, body);
        assertEquals(BASE + "/backup/export/" + AGENT_ID + "?agentVersion=3", sent.get(0).uri().toString());
        assertEquals(Optional.of("Bearer t"), sent.get(0).headers().firstValue("Authorization"));
    }

    @Test
    @DisplayName("the download goes to the approved base URL, whatever host the Location names")
    void locationHostIsNotFollowed() {
        // A source that answered with a host of its choosing would otherwise decide
        // where this deployment sends the caller's bearer token.
        location = "http://attacker.example/backup/export/agent-" + AGENT_ID + "-3.zip";

        RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, "Bearer t");

        URI download = sent.get(1).uri();
        assertEquals("staging.internal", download.getHost());
        assertEquals("/backup/export/agent-" + AGENT_ID + "-3.zip", download.getPath());
    }

    @Test
    @DisplayName("no version means the source's current one, looked up — never the export's default of 1")
    void latestVersionIsResolved() {
        RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, null, null);

        assertTrue(sent.get(1).uri().toString().endsWith("?agentVersion=7"),
                "should export the version the source's descriptor names, got " + sent.get(1).uri());
    }

    @Test
    @DisplayName("a refused export is reported with its status")
    void refusedExport() {
        exportStatus = 403;

        var ex = assertThrows(RemoteReadException.class,
                () -> RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, null));
        assertTrue(ex.getMessage().contains("403"), ex.getMessage());
    }

    @Test
    @DisplayName("an export that names no archive is reported, not downloaded from a guess")
    void noLocation() {
        location = null;

        assertThrows(RemoteReadException.class,
                () -> RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, null));
        assertEquals(1, sent.size(), "nothing may be downloaded without a name to download");
    }

    @Test
    @DisplayName("an archive declared over the cap is refused before its body is read")
    void oversizedArchive() {
        declaredLength = 257L * 1024 * 1024;

        var ex = assertThrows(RemoteReadException.class,
                () -> RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, null));
        assertTrue(ex.getMessage().contains("more than this instance will import"), ex.getMessage());
    }

    @Test
    @DisplayName("a failed download is reported with its status")
    void failedDownload() {
        downloadStatus = 404;

        var ex = assertThrows(RemoteReadException.class,
                () -> RemoteApiResourceSource.exportAgentArchive(client, BASE, AGENT_ID, 3, null));
        assertTrue(ex.getMessage().contains("404"), ex.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> response(int status, Map<String, List<String>> headers, Object body) {
        HttpResponse<T> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn((T) (body instanceof InputStream || body instanceof String ? body : null));
        return response;
    }
}
