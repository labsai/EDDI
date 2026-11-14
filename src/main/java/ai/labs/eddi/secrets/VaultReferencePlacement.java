/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretReference;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where in a model's parameter map a {@code ${vault:…}} reference may sit: in a
 * parameter that carries a credential, and nowhere else.
 *
 * <h2>Why</h2> A chat or embedding model's parameters are resolved against the
 * vault before the model is built, and a resolved value goes wherever its key
 * sends it. In {@code apiKey} it is a credential, sent only in the
 * authentication header. In {@code model}, {@code modelName},
 * {@code deploymentName} or {@code baseUrl} it is <em>data</em>: it travels in
 * the request body or the URL, and comes straight back in the provider's error
 * ("The model {@code sk-live-…} does not exist") — to the RAG search response,
 * the ingestion status, the conversation's {@code taskErrors} — and into the
 * {@code gen_ai.request.model} span attribute and the {@code model} meter tag.
 * An editor with a working key of their own could read any secret of the vault
 * back by writing its reference into the model name.
 * <p>
 * So a reference in any other key is refused, <em>before</em> anything is
 * resolved, with a message naming the key and never its value. Global variables
 * are expanded first by the callers, so a {@code ${vars:…}} that expands to a
 * reference is caught as well.
 */
public final class VaultReferencePlacement {

    private VaultReferencePlacement() {
    }

    /**
     * The first key, in sorted order, whose value holds a vault reference and that
     * is not one of {@code credentialKeys}; {@code null} when there is none.
     */
    public static String findMisplaced(Map<String, String> parameters, Set<String> credentialKeys) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        Set<String> allowed = credentialKeys == null ? Set.of() : credentialKeys;
        for (var entry : new TreeMap<>(parameters).entrySet()) {
            String value = entry.getValue();
            if (value != null && SecretReference.isVaultReference(value) && !allowed.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The refusal for a reference in {@code key}. Names the key and the keys that
     * may carry one — never the value, which is the reference itself or what it
     * expanded to.
     *
     * @param what
     *            whose parameter it is, e.g. {@code "LLM provider 'openai'"}
     */
    public static String describe(String what, String key, Set<String> credentialKeys) {
        String allowed = credentialKeys == null || credentialKeys.isEmpty()
                ? "it takes no credential parameter, so no parameter may"
                : "only " + new TreeSet<>(credentialKeys) + " may";
        return what + ": parameter '" + key + "' holds a ${vault:…} reference, but references are only resolved in the parameters that "
                + "carry a credential, and " + allowed + " carry one. A secret in any other parameter is sent as data and can come back "
                + "in a provider error, a log line or a telemetry attribute, so nothing is built. Move the secret into a credential "
                + "parameter (a token that was part of a URL goes into a credential header, e.g. customHeaders, where the provider "
                + "takes one), and put a literal value, or a ${vars:…} holding one, in '" + key + "'.";
    }

    /**
     * Refuses {@code parameters} when a vault reference sits outside
     * {@code credentialKeys}.
     *
     * @throws MisplacedVaultReferenceException
     *             naming the first such key
     */
    public static void require(Map<String, String> parameters, Set<String> credentialKeys, String what) {
        String key = findMisplaced(parameters, credentialKeys);
        if (key != null) {
            throw new MisplacedVaultReferenceException(describe(what, key, credentialKeys));
        }
    }

    /**
     * A vault reference in a parameter that does not carry a credential. An
     * {@link IllegalArgumentException}, like the other configuration refusals of
     * the model builders, so every caller that reports those reports this one.
     */
    public static class MisplacedVaultReferenceException extends IllegalArgumentException {
        public MisplacedVaultReferenceException(String message) {
            super(message);
        }
    }
}
