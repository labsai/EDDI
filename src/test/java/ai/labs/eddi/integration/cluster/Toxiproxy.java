/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The few calls of the Toxiproxy HTTP API the cluster suite needs: create a
 * proxy, add or remove a toxic, enable or disable a proxy. Plain JSON over
 * HTTP, so the suite needs no client library beyond the JDK.
 */
final class Toxiproxy {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Toxiproxy(String api) {
        this.api = api;
    }

    void createProxy(String name, String listen, String upstream) {
        send("POST", "/proxies", Map.of("name", name, "listen", listen, "upstream", upstream, "enabled", true), 201);
    }

    /** Disabled: every connection through it is closed and new ones refused. */
    void setEnabled(String proxy, boolean enabled) {
        send("POST", "/proxies/" + proxy, Map.of("enabled", enabled), 200);
    }

    /**
     * @param stream
     *            {@code upstream} (client to server) or {@code downstream} (server
     *            to client)
     */
    void addToxic(String proxy, String toxic, String type, String stream, Map<String, Object> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", toxic);
        body.put("type", type);
        body.put("stream", stream);
        body.put("toxicity", 1.0);
        body.put("attributes", attributes);
        send("POST", "/proxies/" + proxy + "/toxics", body, 200);
    }

    void removeToxic(String proxy, String toxic) {
        send("DELETE", "/proxies/" + proxy + "/toxics/" + toxic, null, 204, 404);
    }

    /** Removes every toxic and re-enables every proxy. */
    void reset() {
        send("POST", "/reset", null, 204);
    }

    private void send(String method, String path, Object body, int... expected) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(10));
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (int e : expected) {
                if (r.statusCode() == e) {
                    return;
                }
            }
            throw new IllegalStateException("toxiproxy " + method + " " + path + " -> " + r.statusCode() + " " + r.body());
        } catch (IOException e) {
            throw new IllegalStateException("toxiproxy " + method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
