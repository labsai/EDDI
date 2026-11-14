/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the vault references of a client's parameters and captures, at the
 * point of substitution, the plaintext each one was replaced with — what the
 * client built from the result holds.
 * <p>
 * Each distinct reference is resolved once, on its own, through
 * {@link SecretResolver#resolveSecrets} (the resolver's own semantics: cache,
 * reserved names, a disabled vault leaving it in place), and the values are
 * assembled here. So a by-value scrubber learns exactly the secret each
 * reference stood for: {@code prefix-${vault:key}} captures the secret, not
 * {@code prefix-secret}, and two references in one value, or a secret
 * containing a separator, are captured as the separate secrets they are.
 * Nothing is resolved a second time, so a rotation in between cannot make the
 * captured value differ from the one in the client.
 */
public final class SecretCapture {

    /** The key of the one-entry map each reference is resolved in. */
    private static final String RESOLVED_KEY = "reference";

    private SecretCapture() {
    }

    /**
     * @param uncapturedKeys
     *            parameters whose references are resolved but not captured: an
     *            identity such as a database {@code user}, which is no secret on
     *            its own, and whose capture would withhold every error mentioning
     *            it
     * @return {@code params} with every resolvable reference replaced; one that
     *         could not be resolved stays in place, for
     *         {@link SecretResolver#requireResolved} to refuse
     */
    public static Map<String, String> resolve(SecretResolver secretResolver, Map<String, String> params, Collection<String> captured,
                                              Set<String> uncapturedKeys) {
        Set<String> capturedReferences = new HashSet<>();
        List<String> capturedValues = new ArrayList<>();
        for (Map.Entry<String, String> parameter : params.entrySet()) {
            if (!uncapturedKeys.contains(parameter.getKey()) && parameter.getValue() != null) {
                capturedReferences.addAll(VaultHostAllowlist.referencesIn(List.of(parameter.getValue())));
            }
        }
        Map<String, String> plaintextOf = new HashMap<>();
        for (String reference : VaultHostAllowlist.referencesIn(params.values())) {
            Map<String, String> single = secretResolver.resolveSecrets(Map.of(RESOLVED_KEY, reference));
            String plaintext = single == null ? null : single.get(RESOLVED_KEY);
            if (plaintext != null && !plaintext.equals(reference)) {
                plaintextOf.put(reference, plaintext);
                if (capturedReferences.contains(reference)) {
                    capturedValues.add(plaintext);
                }
            }
        }
        captured.addAll(capturedValues);
        if (plaintextOf.isEmpty()) {
            return params;
        }
        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> value == null || !SecretReference.isVaultReference(value)
                ? value
                : SecretReference.scanner().replaceAll(value, match -> plaintextOf.getOrDefault(match.text(), match.text())));
        return resolved;
    }
}
