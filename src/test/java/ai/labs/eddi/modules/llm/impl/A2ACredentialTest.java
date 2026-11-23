/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretAccess;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What actually lands on an outbound A2A request.
 * <p>
 * The defect these pin: the credential block existed twice — once on the
 * agent-card fetch, once on the task call — and the two had drifted. Only the
 * card fetch understood {@code ${connection:…}}, so an agent configured against
 * a connection discovered its skills perfectly and then sent the literal string
 * {@code Bearer ${connection:salesforce}} as its bearer token on every call it
 * was actually asked to make. Both paths now go through one method, and these
 * tests exercise that method rather than either caller, so a third caller
 * cannot reintroduce a third copy unnoticed.
 */
class A2ACredentialTest {

    private static final String AGENT_URL = "https://peer.example.com/a2a";

    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private ConnectionResolver connectionResolver;

    @BeforeEach
    void setUp() {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        secretResolver = mock(SecretResolver.class);
        connectionResolver = mock(ConnectionResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString(), any())).thenAnswer(i -> i.getArgument(0));
    }

    private A2AToolProviderManager manager() {
        return new A2AToolProviderManager(globalVariableResolver, secretResolver, false, connectionResolver);
    }

    private static A2AAgentConfig config(String apiKey) {
        var config = new A2AAgentConfig();
        config.setName("peer");
        config.setUrl(AGENT_URL);
        config.setApiKey(apiKey);
        return config;
    }

    private static OutboundRequest.Builder request() {
        return OutboundRequest.get(Profile.DEFAULT, URI.create(AGENT_URL));
    }

    /**
     * The first value of header {@code name} on what {@code builder} would send.
     */
    private static String header(OutboundRequest.Builder builder, String name) {
        List<String> values = builder.build().headers().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Test
    @DisplayName("a connection reference is resolved, not sent as literal text")
    void resolvesConnectionReference() {
        when(connectionResolver.resolve(eq("${connection:salesforce}"), any(URI.class), eq(null), any()))
                .thenReturn(new ResolvedCredential("Authorization", "Bearer sf-access-token"));
        var builder = request();

        manager().applyCredential(builder, config("${connection:salesforce}"), AGENT_URL);

        assertEquals("Bearer sf-access-token", header(builder, "Authorization"));
    }

    @Test
    @DisplayName("a connection may name its own header, not just Authorization")
    void honoursTheConnectionsHeaderName() {
        when(connectionResolver.resolve(anyString(), any(URI.class), eq(null), any())).thenReturn(new ResolvedCredential("X-Api-Key", "abc123"));
        var builder = request();

        manager().applyCredential(builder, config("${connection:widget}"), AGENT_URL);

        assertEquals("abc123", header(builder, "X-Api-Key"));
        assertTrue(header(builder, "Authorization") == null, "a connection owns the header name; adding a second one sends two credentials");
    }

    @Test
    @DisplayName("a vault reference still takes the static path and is bearer-prefixed")
    void resolvesStaticKey() {
        when(secretResolver.resolveValue(eq("${vault:peer-key}"), any())).thenReturn("static-token");
        var builder = request();

        manager().applyCredential(builder, config("${vault:peer-key}"), AGENT_URL);

        assertEquals("Bearer static-token", header(builder, "Authorization"));
    }

    @Test
    @DisplayName("no apiKey means no Authorization header, rather than an empty bearer")
    void noKeyMeansNoHeader() {
        var builder = request();

        manager().applyCredential(builder, config(null), AGENT_URL);

        assertTrue(header(builder, "Authorization") == null);
    }

    @Test
    @DisplayName("a scheme spelled out in front of the reference is refused, naming the text that is in the way")
    void refusesASchemePrefix() {
        // A connection supplies the WHOLE header value. "Bearer ${connection:sf}"
        // used to drop the word Bearer silently and send a bare token, and the 401
        // that came back named nothing at all — so the author had no way to learn
        // that the scheme belongs in the connection's valueTemplate instead.
        var builder = request();

        var error = assertThrows(IllegalArgumentException.class,
                () -> manager().applyCredential(builder, config("Bearer ${connection:sf}"), AGENT_URL));

        assertTrue(error.getMessage().contains(AGENT_URL), "the refusal has to say WHICH peer is misconfigured: " + error.getMessage());
        assertTrue(error.getMessage().contains("Bearer"), "and which text is in the way: " + error.getMessage());
        assertTrue(error.getMessage().contains("valueTemplate"), "and where that text belongs instead: " + error.getMessage());
        assertTrue(header(builder, "Authorization") == null, "nothing may go out on a refused credential");
    }

    @Test
    @DisplayName("two references in one value are refused rather than silently using the first")
    void refusesTwoReferences() {
        var builder = request();

        var error = assertThrows(IllegalArgumentException.class,
                () -> manager().applyCredential(builder, config("${connection:sf} ${connection:billing}"), AGENT_URL));

        assertTrue(error.getMessage().contains("a second ${connection:…} reference"),
                "only the first reference is ever parsed, so the second one vanishing has to be said out loud: " + error.getMessage());
        assertTrue(header(builder, "Authorization") == null);
    }

    @Test
    @DisplayName("a connection reference with no resolver fails loudly instead of sending the reference")
    void refusesWithoutAResolver() {
        var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false);
        var builder = request();

        var error = assertThrows(IllegalStateException.class, () -> manager.applyCredential(builder, config("${connection:sf}"), AGENT_URL));

        assertTrue(error.getMessage().contains("ConnectionResolver"), error.getMessage());
    }

    @Test
    @DisplayName("a connection change, here or on another node, evicts the cached Agent Cards (#1179)")
    void connectionChangeEvictsCachedCards() throws Exception {
        var registry = new ConnectionRegistry(mock(IConnectionStore.class));
        var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false,
                new ConnectionResolver(registry, null, null, new SimpleMeterRegistry(), null, false));
        Field cacheField = A2AToolProviderManager.class.getDeclaredField("agentCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (Map<String, A2AToolProviderManager.CachedAgentInfo>) cacheField.get(manager);
        cache.put(AGENT_URL + "|digest-of-the-connection-reference",
                new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "fetched with the old secret"), System.currentTimeMillis()));

        // What the cluster event for a connection written on another node runs.
        registry.invalidateLocal();

        assertEquals(0, manager.getActiveConnectionCount(), "a card fetched under the old binding must not be served");
    }

    @Test
    @DisplayName("a card fetched across an eviction is not published to other callers (#1179)")
    void cardFetchedAcrossAnEvictionIsNotPublished() {
        var manager = manager();
        long generation = manager.cardGeneration();
        var card = new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "fetched with the old secret"), System.currentTimeMillis());

        manager.evictCards(); // the connection changed while the card was being fetched

        assertFalse(manager.publishCard(AGENT_URL, card, generation));
        assertEquals(0, manager.getActiveConnectionCount(), "never readable by another caller, not even briefly");
        assertTrue(manager.publishCard(AGENT_URL, card, manager.cardGeneration()), "a fetch with no eviction across it is cached");
        assertNotNull(manager.cachedCard(AGENT_URL), "and readable: published under the current generation, not the default");
    }

    @Test
    @DisplayName("a replaced vault value evicts the cached Agent Cards (#1179)")
    void secretValueChangeEvictsCachedCards() {
        var manager = manager();
        ArgumentCaptor<SecretResolver.InvalidationInterval> interval = ArgumentCaptor.forClass(SecretResolver.InvalidationInterval.class);
        verify(secretResolver).registerInvalidationInterval(interval.capture());
        manager.publishCard(AGENT_URL, new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "old"), System.currentTimeMillis()),
                manager.cardGeneration());

        interval.getValue().started();
        interval.getValue().finished();

        assertEquals(0, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("no card is published or read while a vault value is being invalidated, before the vault's caches are evicted (#1179)")
    void secretInvalidationIsInProgressBeforeTheEvictions() throws Exception {
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        var peerKey = new SecretReference("default", "peer-key");
        when(provider.resolve(peerKey)).thenReturn("sk-old");
        var vault = new SecretResolver(provider, new SimpleMeterRegistry(), 5, 100);
        vault.init();
        var session = SecretAccess.outsideAgentGrants("test");
        assertEquals("sk-old", vault.resolveValue("${vault:peer-key}", session), "the value is now cached");
        var stillCachedDuring = new AtomicReference<Boolean>();
        var manager = new A2AToolProviderManager(globalVariableResolver, vault, false, connectionResolver);
        var card = new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "peer"), System.currentTimeMillis());
        manager.publishCard(AGENT_URL, card, manager.cardGeneration());
        var publishedDuring = new AtomicReference<Boolean>();
        var readDuring = new AtomicReference<A2AToolProviderManager.CachedAgentInfo>();
        // Registered after the manager: started() runs once the manager's interval has
        // opened and before the vault evicts anything, which is where a discovery could
        // fetch the replacement card on a cache miss.
        vault.registerInvalidationInterval(new SecretResolver.InvalidationInterval() {
            @Override
            public void started() {
                // Pins the order: the vault has evicted nothing yet, so the value still
                // comes from its cache, and the card cache is already closed.
                vault.resolveValue("${vault:peer-key}", session);
                stillCachedDuring.set(mockingDetails(provider).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("resolve")).count() == 1);
                readDuring.set(manager.cachedCard(AGENT_URL));
                publishedDuring.set(manager.publishCard(AGENT_URL, card, manager.cardGeneration()));
            }

            @Override
            public void finished() {
            }
        });

        vault.invalidateLocal(peerKey);

        assertTrue(stillCachedDuring.get(), "the interval opens before the vault evicts anything");
        assertNull(readDuring.get(), "the card fetched before the replacement is not read while it is invalidated");
        assertFalse(publishedDuring.get(), "nor is one fetched during the invalidation published");
        assertNull(manager.cachedCard(AGENT_URL), "and the old card is gone afterwards");
    }

    @Test
    @DisplayName("a card that slipped in between an eviction's counter bump and its clear is never read (#1179)")
    void cardInsertedDuringAnEvictionIsNotRead() throws Exception {
        var manager = manager();
        long before = manager.cardGeneration();
        Field cacheField = A2AToolProviderManager.class.getDeclaredField("agentCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (Map<String, A2AToolProviderManager.CachedAgentInfo>) cacheField.get(manager);
        var seen = new AtomicReference<A2AToolProviderManager.CachedAgentInfo>();
        manager.evictionStartedHook = () -> {
            // The interleaving: a publication whose generation check passed before the
            // bump lands now, and another caller reads before the clear.
            cache.put(AGENT_URL, new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "old secret's card"),
                    System.currentTimeMillis(), before));
            seen.set(manager.cachedCard(AGENT_URL));
        };

        manager.evictCards();

        assertNull(seen.get(), "the card was published under the generation the eviction retired");
    }

    @Test
    @DisplayName("a credential changed during discovery has its grant read again before the peer's tools are handed out (#1179)")
    void credentialChangedDuringDiscoveryIsCheckedAgain() {
        var manager = manager();
        String key = A2AToolProviderManager.cardCacheKey(AGENT_URL, "${vault:peer-key}");
        doAnswer(invocation -> {
            // Passed for the old credential; meanwhile the value is replaced and a card
            // fetched with the new one is published.
            manager.evictCards();
            manager.publishCard(key, new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "peer", "description", "new key's card"),
                    System.currentTimeMillis()), manager.cardGeneration());
            return null;
        }).doThrow(new SecretResolver.SecretNotGrantedException("Vault secret ${vault:peer-key} is not resolved for agent-a"))
                .when(secretResolver).requireGranted(any(Iterable.class), any(), anyString());

        var result = manager.discoverTools(List.of(config("${vault:peer-key}")), SecretAccess.agent("agent-a"));

        assertTrue(result.toolSpecs().isEmpty(), "the new card's tools must not reach an agent the new grant leaves out");
        assertTrue(result.executors().isEmpty());
        verify(secretResolver, times(2)).requireGranted(any(Iterable.class), any(), anyString());
    }

    @Test
    @DisplayName("a discovery with no credential change reads the grant once and keeps the tools")
    void unchangedCredentialIsCheckedOnce() {
        var manager = manager();
        String key = A2AToolProviderManager.cardCacheKey(AGENT_URL, "${vault:peer-key}");
        manager.publishCard(key, new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "peer", "description", "card"),
                System.currentTimeMillis()), manager.cardGeneration());

        var result = manager.discoverTools(List.of(config("${vault:peer-key}")), SecretAccess.agent("agent-a"));

        assertEquals(1, result.toolSpecs().size());
        verify(secretResolver, times(1)).requireGranted(any(Iterable.class), any(), anyString());
    }

    @Test
    @DisplayName("no card fetched under a connection's old binding is published or read while its invalidation runs (#1179)")
    void connectionInvalidationIsInProgressThroughout() {
        var registry = new ConnectionRegistry(mock(IConnectionStore.class));
        var card = new A2AToolProviderManager.CachedAgentInfo(Map.of("name", "fetched with secret A"), System.currentTimeMillis());
        var published = new AtomicReference<Boolean>();
        var readAfterDrop = new AtomicReference<A2AToolProviderManager.CachedAgentInfo>();
        var holder = new AtomicReference<A2AToolProviderManager>();
        // Registered before the manager: its finished() runs after the registry dropped
        // its documents and before the manager is told the invalidation finished.
        registry.onInvalidation(new ConnectionRegistry.InvalidationListener() {
            @Override
            public void started() {
            }

            @Override
            public void finished() {
                // A caller granted secret B loads the new binding and asks for the card.
                readAfterDrop.set(holder.get().cachedCard(AGENT_URL));
            }
        });
        var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false,
                new ConnectionResolver(registry, null, null, new SimpleMeterRegistry(), null, false));
        holder.set(manager);
        // Registered after: its started() runs after the manager's eviction started and
        // before the registry drops its documents, while the old binding is still
        // served.
        registry.onInvalidation(new ConnectionRegistry.InvalidationListener() {
            @Override
            public void started() {
                published.set(manager.publishCard(AGENT_URL, card, manager.cardGeneration()));
            }

            @Override
            public void finished() {
            }
        });

        registry.invalidateLocal();

        assertFalse(published.get(), "a card fetched during the invalidation, with the old binding, is not published");
        assertNull(readAfterDrop.get(), "nor read after the registry dropped the old binding");
        assertTrue(manager.publishCard(AGENT_URL, card, manager.cardGeneration()), "once it finished, cards are cached again");
    }

    @Test
    @DisplayName("a discovery that starts while an eviction runs reads the grant again before handing out the tools (#1179)")
    void discoveryStartedDuringAnEvictionIsCheckedAgain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] cardJson = "{\"name\":\"peer\",\"description\":\"fetched during the eviction\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/agent.json", exchange -> {
            exchange.sendResponseHeaders(200, cardJson.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(cardJson);
            }
        });
        server.start();
        try {
            var registry = new ConnectionRegistry(mock(IConnectionStore.class));
            var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false,
                    new ConnectionResolver(registry, null, null, new SimpleMeterRegistry(), null, false));
            var peer = config("${vault:peer-key}");
            peer.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            peer.setTimeoutMs(5000L);
            // The first grant read passes (the old binding's); the one after the fetch
            // refuses.
            doNothing().doThrow(new SecretResolver.SecretNotGrantedException("Vault secret ${vault:peer-key} is not resolved for agent-a"))
                    .when(secretResolver).requireGranted(any(Iterable.class), any(), anyString());
            var result = new AtomicReference<A2AToolProviderManager.A2AToolsResult>();
            // Registered after the manager: discovery runs inside the open interval, where
            // the counter it samples does not move again before it ends.
            registry.onInvalidation(new ConnectionRegistry.InvalidationListener() {
                @Override
                public void started() {
                    result.set(manager.discoverTools(List.of(peer), SecretAccess.agent("agent-a")));
                }

                @Override
                public void finished() {
                }
            });

            registry.invalidateLocal();

            assertTrue(result.get().toolSpecs().isEmpty(), "the card fetched during the eviction reached an agent the new grant refuses");
            verify(secretResolver, times(2)).requireGranted(any(Iterable.class), any(), anyString());
        } finally {
            server.stop(0);
        }
    }
}
