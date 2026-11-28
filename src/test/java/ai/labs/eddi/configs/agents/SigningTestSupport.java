/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link AgentSigningService} over an in-memory vault, for tests outside
 * this package that sign and verify with real Ed25519 keys rather than a
 * stubbed service.
 */
public final class SigningTestSupport {

    private SigningTestSupport() {
    }

    /**
     * A vault holding plaintext in a map, keyed {@code tenantId:keyName}. It models
     * what agent signing uses — {@code resolve}, the atomic {@code storeIfAbsent}
     * and {@code delete} — and nothing else; every other call answers Mockito's
     * default. Stub further on the returned mock to make a call fail.
     */
    public static ISecretProvider inMemoryVault() throws Exception {
        Map<String, String> secrets = new ConcurrentHashMap<>();
        ISecretProvider vault = mock(ISecretProvider.class);
        when(vault.resolve(any())).thenAnswer(invocation -> {
            String value = secrets.get(key(invocation.getArgument(0)));
            if (value == null) {
                throw new ISecretProvider.SecretNotFoundException("Secret not found: " + key(invocation.getArgument(0)));
            }
            return value;
        });
        when(vault.storeIfAbsent(any(), anyString(), any(), any()))
                .thenAnswer(invocation -> secrets.putIfAbsent(key(invocation.getArgument(0)), invocation.getArgument(1)) == null);
        doAnswer(invocation -> {
            if (secrets.remove(key(invocation.getArgument(0))) == null) {
                throw new ISecretProvider.SecretNotFoundException("Secret not found: " + key(invocation.getArgument(0)));
            }
            return null;
        }).when(vault).delete(any());
        when(vault.isAvailable()).thenReturn(true);
        return vault;
    }

    /** The service as the container builds it, metrics included. */
    public static AgentSigningService signingService(ISecretProvider vault, MeterRegistry meterRegistry) {
        AgentSigningService service = new AgentSigningService(vault, meterRegistry);
        service.initMetrics();
        return service;
    }

    private static String key(SecretReference reference) {
        return reference.tenantId() + ":" + reference.keyName();
    }
}
