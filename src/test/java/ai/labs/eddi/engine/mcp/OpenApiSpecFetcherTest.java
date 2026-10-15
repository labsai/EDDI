/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote OpenAPI spec is fetched by EDDI, not by swagger-parser: every
 * redirect hop is held to the target policy and the body is capped. The spec
 * server is an embedded one on loopback, so the "protection on" cases use
 * {@code eddi.self.base-url} pointed at it — the one address the policy
 * exempts.
 */
@DisplayName("OpenApiSpecFetcher")
class OpenApiSpecFetcherTest {

    private static final String SPEC = """
            {"openapi":"3.0.0","info":{"title":"Fetched","version":"1"},
             "paths":{"/items":{"get":{"operationId":"listItems","responses":{"200":{"description":"ok"}}}}}}
            """;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private String origin;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/openapi.json", exchange -> respond(exchange, 200, SPEC));
        server.createContext("/moved", exchange -> redirect(exchange, "/openapi.json"));
        server.createContext("/relative", exchange -> redirect(exchange, "openapi.json"));
        server.createContext("/to-metadata", exchange -> redirect(exchange, "http://169.254.169.254/latest/meta-data/openapi.json"));
        server.createContext("/to-other-loopback",
                exchange -> redirect(exchange, "http://127.0.0.2:" + server.getAddress().getPort() + "/openapi.json"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "no"));
        server.createContext("/no-location", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> respond(exchange, 200, "{\"openapi\":\"3.0.0\",\"x-pad\":\"" + "a".repeat(4096) + "\"}"));
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        requests.add(exchange.getRequestURI().getPath());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        requests.add(exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static OpenApiSpecFetcher fetcher(OutboundTargetPolicy policy) {
        return new OpenApiSpecFetcher(new SafeHttpClient(5_000), policy);
    }

    private OutboundTargetPolicy protectionOnExemptingThisServer() {
        return new OutboundTargetPolicy(true, new SelfUrlResolver(Optional.of(origin), 7070), InetAddress::getAllByName);
    }

    @Test
    @DisplayName("protection off: the document is fetched and parsed, following an absolute and a relative redirect")
    void fetchesThroughRedirects() {
        var fetcher = fetcher(OutboundTargetPolicy.permissive());
        var api = McpApiToolBuilder.parseSpec(origin + "/moved", fetcher);
        assertEquals("Fetched", api.getInfo().getTitle());
        assertNotNull(api.getPaths().get("/items"));
        assertEquals(List.of("/moved", "/openapi.json"), requests);

        requests.clear();
        assertEquals("Fetched", McpApiToolBuilder.parseSpec(origin + "/relative", fetcher).getInfo().getTitle());
        assertEquals(List.of("/relative", "/openapi.json"), requests);
    }

    @Test
    @DisplayName("protection off: a redirect onto the metadata service is refused, and the metadata host is never contacted")
    void redirectToMetadataRefused() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.parseSpec(origin + "/to-metadata", fetcher(OutboundTargetPolicy.permissive())));
        assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        assertEquals(List.of("/to-metadata"), requests);
    }

    @Test
    @DisplayName("protection on: a loopback spec location is refused before any request")
    void protectionOnRefusesLoopback() {
        var policy = new OutboundTargetPolicy(true, null, InetAddress::getAllByName);
        var e = assertThrows(IllegalArgumentException.class, () -> fetcher(policy).fetch(origin + "/openapi.json"));
        assertTrue(e.getMessage().contains("internal/local") || e.getMessage().contains("private/internal"), e.getMessage());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    @DisplayName("protection on: this deployment's own origin is fetched, but a redirect off it to another private address is refused")
    void protectionOnFollowsSelfRefusesPrivateHop() {
        var fetcher = fetcher(protectionOnExemptingThisServer());
        assertEquals("Fetched", McpApiToolBuilder.parseSpec(origin + "/moved", fetcher).getInfo().getTitle());

        requests.clear();
        var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(origin + "/to-other-loopback"));
        assertTrue(e.getMessage().contains("private/internal"), e.getMessage());
        assertEquals(List.of("/to-other-loopback"), requests, "the second hop was refused, not requested");
    }

    @Test
    @DisplayName("a body over the cap is refused while streaming")
    void bodyCapEnforced() {
        var small = new OpenApiSpecFetcher(new SafeHttpClient(5_000), OutboundTargetPolicy.permissive(), 1024);
        var e = assertThrows(IllegalArgumentException.class, () -> small.fetch(origin + "/huge"));
        assertTrue(e.getMessage().contains("larger than the 1024 byte limit"), e.getMessage());
    }

    @Test
    @DisplayName("a non-200 answer, a redirect loop and a redirect without Location each fail with a message naming the cause")
    void failuresAreNamed() {
        var fetcher = fetcher(OutboundTargetPolicy.permissive());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(origin + "/missing")).getMessage().contains("HTTP 404"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(origin + "/loop")).getMessage()
                .contains("redirected more than " + OpenApiSpecFetcher.MAX_REDIRECTS));
        assertEquals(OpenApiSpecFetcher.MAX_REDIRECTS + 1, requests.stream().filter("/loop"::equals).count());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(origin + "/no-location")).getMessage()
                .contains("without a Location header"));
    }

    @Test
    @DisplayName("a non-http location never reaches the fetcher, and a connection failure is reported as a parse-level refusal")
    void schemeAndConnectFailures() {
        var fetcher = fetcher(OutboundTargetPolicy.permissive());
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch("file:///etc/passwd"));
        server.stop(0);
        var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(origin + "/openapi.json"));
        assertTrue(e.getMessage().startsWith("Could not fetch the OpenAPI spec from " + origin), e.getMessage());
    }

    @Test
    @DisplayName("messages name scheme, host, port and path only — never userinfo or a query")
    void messagesCarryNoCredentials() {
        assertEquals("https://host.example:8443/spec.json",
                OpenApiSpecFetcher.forMessage(URI.create("https://user:secret@host.example:8443/spec.json?token=abc#frag")));
    }

    @Test
    @DisplayName("the fetcher-less parseSpec still works for inline content and for a remote spec with protection off")
    void fetcherlessOverloadIsPermissive() {
        assertNotNull(McpApiToolBuilder.parseSpec(SPEC));
        assertEquals("Fetched", McpApiToolBuilder.parseSpec(origin + "/openapi.json").getInfo().getTitle());
    }
}
