/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ImportVaultReferences")
class ImportVaultReferencesTest {

    private static final String CONFIG = "{\"tasks\":[{\"parameters\":{\"apiKey\":\"${vault:openai-key}\","
            + "\"baseUrl\":\"https://x.example/v1?k=${vault:acme/other}\",\"again\":\"${vault:openai-key}\"}}]}";

    @Test
    @DisplayName("every vault reference this instance lacks is named once, wherever it sits (I2)")
    void namesMissingReferences() {
        List<String> problems = ImportVaultReferences.missing(CONFIG, reference -> false);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("${vault:openai-key}"), problems.get(0));
        assertTrue(problems.get(1).contains("${vault:acme/other}"), problems.get(1));
    }

    @Test
    @DisplayName("a reference the instance has is not reported; no reference, no warning")
    void presentIsSilent() {
        assertTrue(ImportVaultReferences.missing(CONFIG, reference -> true).isEmpty());
        assertTrue(ImportVaultReferences.missing("{\"a\":\"plain\"}", reference -> false).isEmpty());
        assertTrue(ImportVaultReferences.missing(null, reference -> false).isEmpty());
    }

    @Test
    @DisplayName("only 'not found' counts as missing: a provider failure never produces a false warning")
    void providerFailureIsNotMissing() throws Exception {
        var provider = mock(ISecretProvider.class);
        when(provider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("no"));
        assertFalse(ImportVaultReferences.existsIn(provider).test(new SecretReference("default", "k")));

        var broken = mock(ISecretProvider.class);
        when(broken.getMetadata(any())).thenThrow(new ISecretProvider.SecretProviderException("db down"));
        assertTrue(ImportVaultReferences.existsIn(broken).test(new SecretReference("default", "k")));
    }
}
