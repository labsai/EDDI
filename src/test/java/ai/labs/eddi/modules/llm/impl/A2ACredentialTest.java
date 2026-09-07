/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        lenient().when(secretResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
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

    private static HttpRequest.Builder request() {
        return HttpRequest.newBuilder().uri(URI.create(AGENT_URL)).GET();
    }

    @Test
    @DisplayName("a connection reference is resolved, not sent as literal text")
    void resolvesConnectionReference() {
        when(connectionResolver.resolve(eq("${connection:salesforce}"), any(URI.class), eq(null)))
                .thenReturn(new ResolvedCredential("Authorization", "Bearer sf-access-token"));
        var builder = request();

        manager().applyCredential(builder, config("${connection:salesforce}"), AGENT_URL);

        assertEquals("Bearer sf-access-token", builder.build().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    @DisplayName("a connection may name its own header, not just Authorization")
    void honoursTheConnectionsHeaderName() {
        when(connectionResolver.resolve(anyString(), any(URI.class), eq(null))).thenReturn(new ResolvedCredential("X-Api-Key", "abc123"));
        var builder = request();

        manager().applyCredential(builder, config("${connection:widget}"), AGENT_URL);

        var headers = builder.build().headers();
        assertEquals("abc123", headers.firstValue("X-Api-Key").orElseThrow());
        assertTrue(headers.firstValue("Authorization").isEmpty(), "a connection owns the header name; adding a second one sends two credentials");
    }

    @Test
    @DisplayName("a vault reference still takes the static path and is bearer-prefixed")
    void resolvesStaticKey() {
        when(secretResolver.resolveValue("${vault:peer-key}")).thenReturn("static-token");
        var builder = request();

        manager().applyCredential(builder, config("${vault:peer-key}"), AGENT_URL);

        assertEquals("Bearer static-token", builder.build().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    @DisplayName("no apiKey means no Authorization header, rather than an empty bearer")
    void noKeyMeansNoHeader() {
        var builder = request();

        manager().applyCredential(builder, config(null), AGENT_URL);

        assertTrue(builder.build().headers().firstValue("Authorization").isEmpty());
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
        assertTrue(builder.build().headers().firstValue("Authorization").isEmpty(), "nothing may go out on a refused credential");
    }

    @Test
    @DisplayName("two references in one value are refused rather than silently using the first")
    void refusesTwoReferences() {
        var builder = request();

        var error = assertThrows(IllegalArgumentException.class,
                () -> manager().applyCredential(builder, config("${connection:sf} ${connection:billing}"), AGENT_URL));

        assertTrue(error.getMessage().contains("a second ${connection:…} reference"),
                "only the first reference is ever parsed, so the second one vanishing has to be said out loud: " + error.getMessage());
        assertTrue(builder.build().headers().firstValue("Authorization").isEmpty());
    }

    @Test
    @DisplayName("a connection reference with no resolver fails loudly instead of sending the reference")
    void refusesWithoutAResolver() {
        var manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false);
        var builder = request();

        var error = assertThrows(IllegalStateException.class, () -> manager.applyCredential(builder, config("${connection:sf}"), AGENT_URL));

        assertTrue(error.getMessage().contains("ConnectionResolver"), error.getMessage());
    }
}
