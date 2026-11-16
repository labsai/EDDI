/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentCallMarker — which targets are this deployment")
class AgentCallMarkerTest {

    private final AgentCallMarker marker = new AgentCallMarker(Set.of("eddi.example.com", "eddi.internal:7070"));

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:7070/hitl/samples/s1", "http://localhost:7070/x", "http://[::1]:7070/x", "http://127.0.0.5/x",
            "https://eddi.example.com/hitl/autonomy/a/apply", "https://EDDI.example.com:8443/x", "http://eddi.internal:7070/x"})
    @DisplayName("loopback, the self origin and the configured self hosts are marked")
    void self(String url) {
        assertTrue(marker.targetsSelf(URI.create(url)), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.com/x", "https://eddi.example.com.attacker.io/x", "http://eddi.internal:7071/x",
            "http://10.0.0.1:7070/x", "http://localhost.evil.com/x", "mailto:someone@example.com"})
    @DisplayName("every other host is not")
    void other(String url) {
        assertFalse(marker.targetsSelf(URI.create(url)), url);
    }

    @Test
    @DisplayName("the deployment's own origin from SelfUrlResolver counts")
    @SuppressWarnings("unchecked")
    void selfUrlResolver() {
        Instance<SelfUrlResolver> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(new SelfUrlResolver(Optional.of("https://chat.acme.io"), 7070));
        var resolverMarker = new AgentCallMarker(instance, Optional.empty());

        assertTrue(resolverMarker.targetsSelf(URI.create("https://chat.acme.io/hitl/samples/s1")));
        assertFalse(resolverMarker.targetsSelf(URI.create("https://other.acme.io/hitl/samples/s1")));
    }

    @Test
    @DisplayName("apply writes the header only for a self target; without a marker bean only loopback is marked")
    void apply() {
        IRequest third = mock(IRequest.class);
        marker.apply(third, URI.create("https://api.example.com/x"));
        verify(third, never()).setHttpHeader(any(), any());

        IRequest self = mock(IRequest.class);
        marker.apply(self, URI.create("https://eddi.example.com/x"));
        verify(self).setHttpHeader(AgentCallMarker.HEADER, AgentCallMarker.VALUE);

        IRequest fallback = mock(IRequest.class);
        AgentCallMarker.apply(null, fallback, URI.create("https://eddi.example.com/x"));
        verify(fallback, never()).setHttpHeader(any(), any());
        AgentCallMarker.apply(null, fallback, URI.create("http://127.0.0.1:7070/x"));
        verify(fallback).setHttpHeader(AgentCallMarker.HEADER, AgentCallMarker.VALUE);
    }
}
