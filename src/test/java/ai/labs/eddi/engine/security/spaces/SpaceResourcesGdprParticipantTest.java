/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A person's own space holds secrets and variables they put there, which an
 * erasure must remove and an export must list — and a team's space holds what
 * the team put there, which neither may touch.
 */
@DisplayName("GDPR: a person's own space")
class SpaceResourcesGdprParticipantTest {

    private static final String ALICE_TENANT = SpaceTenants.tenantFor(Subjects.personalSpace("alice"));

    private ISecretProvider vault;
    private IGlobalVariableStore variables;
    private GlobalVariableResolver resolver;
    private SpaceResourcesGdprParticipant sut;

    @BeforeEach
    void setUp() {
        vault = mock(ISecretProvider.class);
        variables = mock(IGlobalVariableStore.class);
        resolver = mock(GlobalVariableResolver.class);
        when(vault.isAvailable()).thenReturn(true);
        sut = new SpaceResourcesGdprParticipant(vault, variables, resolver);
    }

    private static SecretMetadata secret(String key) {
        return new SecretMetadata(ALICE_TENANT, key, Instant.EPOCH, null, null, "sum", "my key", List.of("*"));
    }

    @Test
    @DisplayName("erasure removes the person's own secrets and variables, and only those")
    void eraseOwnSpaceOnly() throws Exception {
        when(vault.listKeys(ALICE_TENANT)).thenReturn(List.of(secret("openai")));
        when(variables.listAll(ALICE_TENANT)).thenReturn(List.of(new GlobalVariable(ALICE_TENANT, "model", "gpt-x", null, false)));

        assertEquals(2, sut.erase("alice"));

        verify(vault).delete(new SecretReference(ALICE_TENANT, "openai"));
        verify(variables).delete(ALICE_TENANT, "model");
        verify(resolver).invalidateCache();
        // Only alice's tenant was listed; no team tenant was touched.
        verify(vault, never()).listKeys(SpaceTenants.tenantFor(Subjects.teamSpace("engineering")));
    }

    @Test
    @DisplayName("the export names the secrets but never carries their values")
    @SuppressWarnings("unchecked")
    void exportWithoutSecretValues() throws Exception {
        when(vault.listKeys(ALICE_TENANT)).thenReturn(List.of(secret("openai")));
        when(variables.listAll(ALICE_TENANT)).thenReturn(List.of(new GlobalVariable(ALICE_TENANT, "model", "gpt-x", null, false)));

        var held = (Map<String, Object>) sut.export("alice");

        var secrets = (List<Map<String, Object>>) held.get("secrets");
        assertEquals("openai", secrets.get(0).get("keyName"));
        assertFalse(secrets.get(0).containsKey("checksum"));
        verify(vault, never()).resolve(any());
        assertEquals(1, ((List<?>) held.get("variables")).size());
    }

    @Test
    @DisplayName("nothing held, nothing exported; a vault that is off is simply skipped")
    void nothingHeld() throws Exception {
        when(vault.isAvailable()).thenReturn(false);
        when(variables.listAll(anyString())).thenReturn(List.of());

        assertNull(sut.export("alice"));
        assertEquals(0, sut.erase("alice"));
        verify(vault, never()).listKeys(anyString());
    }
}
