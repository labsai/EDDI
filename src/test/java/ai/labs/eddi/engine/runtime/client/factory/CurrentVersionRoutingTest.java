/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A REST client runs {@code default} methods locally, so
 * {@code getCurrentVersion} on a loopback store client hit the mixin's
 * fail-closed {@code requireCurrentVersionAccess} and refused everyone. That
 * failed {@code setup-api/sync} with an HTTP 500 and every MCP tool that reads
 * "the latest version". The routed client sends it to the endpoint instead.
 */
class CurrentVersionRoutingTest {

    private static final String BASE = "http://127.0.0.1:7070";

    @Path("/teststore/things")
    public interface IRestThingStore extends IRestVersionInfo {
        @GET
        @Path("/{id}")
        String read(@PathParam("id") String id);
    }

    /**
     * What a REST client proxy is to the caller: the interface's default methods
     * run here, and nothing overrides {@code requireCurrentVersionAccess}.
     */
    private static final class ClientLikeThingStore implements IRestThingStore {
        @Override
        public String read(String id) {
            if ("missing".equals(id)) {
                throw new NotFoundException("no " + id);
            }
            return "thing " + id;
        }

        @Override
        public String getResourceURI() {
            return "eddi://ai.labs.test/teststore/things/";
        }
    }

    private final List<URI> builtFor = new ArrayList<>();
    private final List<String> askedFor = new ArrayList<>();

    private IRestThingStore routed() {
        return CurrentVersionRouting.route(IRestThingStore.class, new ClientLikeThingStore(), BASE, uri -> {
            builtFor.add(uri);
            return id -> {
                askedFor.add(id);
                return " 7\n";
            };
        });
    }

    @Test
    void theUnroutedClientRefusesEveryone() {
        var raw = new ClientLikeThingStore();

        var denied = assertThrows(ForbiddenException.class, () -> raw.getCurrentVersion("abc"));
        assertTrue(denied.getMessage().contains("does not define how its version info is authorized"));
    }

    @Test
    void getCurrentVersionGoesToTheStoresEndpoint() {
        var store = routed();

        assertEquals(7, store.getCurrentVersion("abc"));
        assertEquals(List.of(URI.create(BASE + "/teststore/things")), builtFor);
        assertEquals(List.of("abc"), askedFor);
    }

    @Test
    void theEndpointClientIsBuiltOnce() {
        var store = routed();

        store.getCurrentVersion("a");
        store.getCurrentVersion("b");

        assertEquals(1, builtFor.size());
        assertEquals(List.of("a", "b"), askedFor);
    }

    @Test
    void everyOtherMethodReachesTheClient() {
        var store = routed();

        assertEquals("thing abc", store.read("abc"));
        assertEquals("eddi://ai.labs.test/teststore/things/", store.getResourceURI());
        assertTrue(askedFor.isEmpty());
    }

    @Test
    void theClientsExceptionsArriveUnwrapped() {
        var store = routed();

        assertThrows(NotFoundException.class, () -> store.read("missing"));
    }

    @Test
    void anEndpointFailureArrivesAsItself() {
        var store = CurrentVersionRouting.route(IRestThingStore.class, new ClientLikeThingStore(), BASE, uri -> id -> {
            throw new ForbiddenException("HTTP 403");
        });

        var denied = assertThrows(ForbiddenException.class, () -> store.getCurrentVersion("abc"));
        assertEquals("HTTP 403", denied.getMessage());
    }

    @Test
    void aRealStoreInterfaceIsAddressedByItsOwnPath() {
        IRestWorkflowStore client = mock(IRestWorkflowStore.class);
        List<URI> uris = new ArrayList<>();

        IRestWorkflowStore store = CurrentVersionRouting.route(IRestWorkflowStore.class, client, BASE + "/", uri -> {
            uris.add(uri);
            return id -> "3";
        });

        assertEquals(3, store.getCurrentVersion("wf"));
        assertEquals(List.of(URI.create(BASE + "/workflowstore/workflows")), uris);
    }

    @Test
    void anInterfaceWithoutVersionInfoIsLeftAlone() {
        Runnable client = () -> {
        };

        assertSame(client, CurrentVersionRouting.route(Runnable.class, client, BASE, uri -> {
            throw new AssertionError("no endpoint for a type without version info");
        }));
    }

    @Test
    void storeUriJoinsWithOneSlash() {
        assertEquals(URI.create("http://h:1/a/b"), CurrentVersionRouting.storeUri("http://h:1/", "/a/b"));
        assertEquals(URI.create("http://h:1/a/b"), CurrentVersionRouting.storeUri("http://h:1", "a/b"));
    }
}
