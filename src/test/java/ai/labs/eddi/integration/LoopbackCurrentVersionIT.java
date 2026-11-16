/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.LoopbackCallerAuthFilter;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real loopback call behind {@code getCurrentVersion} on a store client
 * from {@link IRestInterfaceFactory}.
 * <p>
 * {@code CurrentVersionRoutingTest} stubs the HTTP layer, so it proves the
 * method is rerouted but not that a request actually leaves the client, reaches
 * the store's {@code GET /{id}/currentversion} endpoint and comes back as the
 * right number. This test does both halves:
 * <ul>
 * <li>against the running application: the injected factory resolves the real
 * latest version of a stored resource through the real endpoint, role check and
 * VIEW check (before the fix this threw "does not define how its version info
 * is authorized")</li>
 * <li>against a JDK {@code HttpServer} on an ephemeral loopback port standing
 * in for the listener: the request has the expected method and path, the
 * single-argument {@code get} forwards the caller's bearer token, and the
 * two-argument {@code get} (a named remote instance) forwards none</li>
 * </ul>
 * The second half proves the client behaviour only, not the server's answer to
 * it.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DisplayName("Loopback getCurrentVersion — real HTTP call")
public class LoopbackCurrentVersionIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/propertysetterstore/propertysetters/";
    private static final String BODY = """
            {"setOnActions":[{"actions":["a"],"setProperties":[{"name":"p","valueString":"v","scope":"conversation"}]}]}
            """;

    @Inject
    IRestInterfaceFactory factory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("resolves the real latest version of a stored resource through the application's own port")
    void resolvesLatestVersionAgainstTheRunningApplication() throws Exception {
        ResourceId created = extractResourceId(createResource(BODY, ROOT_PATH));
        try {
            given().body(BODY).contentType(ContentType.JSON)
                    .put(ROOT_PATH + created.id() + VERSION_STRING + created.version())
                    .then().statusCode(200);

            IRestPropertySetterStore store = factory.get(IRestPropertySetterStore.class);

            assertEquals(2, store.getCurrentVersion(created.id()));
        } finally {
            deleteResourceQuietly(ROOT_PATH, created.id(), created.version());
        }
    }

    @Test
    @DisplayName("sends GET /{id}/currentversion with the caller's bearer token on the loopback client")
    void loopbackClientForwardsTheCallerToken() throws Exception {
        List<String> seen = startServer();
        CallerIdentityContext context = mock(CallerIdentityContext.class);
        when(context.capture()).thenReturn(new CallerIdentity("tok-123", "alice", "http://127.0.0.1"));
        var loopback = new RestInterfaceFactory(server.getAddress().getPort(), "127.0.0.1", new LoopbackCallerAuthFilter(context));

        Integer version = loopback.get(IRestPropertySetterStore.class).getCurrentVersion("abc");

        assertEquals(5, version);
        assertEquals(List.of("GET /propertysetterstore/propertysetters/abc/currentversion auth=Bearer tok-123"), seen);
    }

    @Test
    @DisplayName("sends the request to a named remote instance without any caller credential")
    void remoteClientForwardsNoToken() throws Exception {
        List<String> seen = startServer();
        var remote = new RestInterfaceFactory(7070, "127.0.0.1", mock(LoopbackCallerAuthFilter.class));

        Integer version = remote.get(IRestPropertySetterStore.class, "http://127.0.0.1:" + server.getAddress().getPort())
                .getCurrentVersion("abc");

        assertEquals(5, version);
        assertEquals(List.of("GET /propertysetterstore/propertysetters/abc/currentversion auth=null"), seen);
    }

    /**
     * Answers every request with version {@code 5} and records method, path and
     * Authorization.
     */
    private List<String> startServer() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "5".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return seen;
    }
}
