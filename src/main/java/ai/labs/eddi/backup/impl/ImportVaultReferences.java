/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.model.ReferenceScanner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the vault references of an imported configuration that this instance
 * cannot resolve.
 * <p>
 * A vault secret never travels with an archive, so an LLM config imported from
 * another instance routinely names secrets that exist only there. The import
 * stays lenient — the config is written — but the caller is told, because the
 * agent then deploys {@code READY} and fails on its first turn.
 * <p>
 * Names the reference text only, never a value.
 */
final class ImportVaultReferences {

    private ImportVaultReferences() {
    }

    /**
     * @param serializedConfig
     *            the configuration as JSON
     * @param exists
     *            whether this instance has the secret
     * @return one message per distinct missing reference
     */
    static List<String> missing(String serializedConfig, Predicate<SecretReference> exists) {
        List<String> problems = new ArrayList<>();
        if (serializedConfig == null || serializedConfig.isEmpty()) {
            return problems;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ReferenceScanner.Match match : SecretReference.scanner().findAll(serializedConfig)) {
            if (!seen.add(match.text())) {
                continue;
            }
            if (!exists.test(SecretReference.of(match))) {
                problems.add("the vault reference " + match.text() + " does not exist on this instance: create that secret before "
                        + "deploying the agent, or it fails on its first turn");
            }
        }
        return problems;
    }

    /**
     * A secret exists unless the provider says it is not found; any other failure
     * is not "missing".
     */
    static Predicate<SecretReference> existsIn(ISecretProvider provider) {
        return reference -> {
            try {
                provider.getMetadata(reference);
                return true;
            } catch (ISecretProvider.SecretNotFoundException e) {
                return false;
            } catch (Exception e) {
                return true;
            }
        };
    }
}
