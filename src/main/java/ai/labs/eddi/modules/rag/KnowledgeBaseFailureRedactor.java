/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.EmbeddingParameters;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.StoreParameters;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.memory.SecretValueScrubber;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes an embedding-model or vector-store failure safe to hand back to the
 * caller of a knowledge base's endpoints.
 * <p>
 * Those failures are reported on purpose — a bad key or an unreachable store is
 * what an operator opens the search view or polls the ingestion status to find
 * out — but the message is the provider's, and a provider may quote what it was
 * sent. {@code VaultReferencePlacement} keeps a secret out of every parameter
 * but the credential; this is the second layer, for whatever a provider still
 * echoes: the literal value of each credential parameter, and every plaintext
 * the knowledge base's own vault references resolve to is removed by value,
 * then credential shapes are redacted.
 * <p>
 * Resolving the references here releases nothing: the plaintexts are used only
 * to find and remove them, and the same references were resolved a moment ago
 * to build the failing model or store.
 */
@ApplicationScoped
public class KnowledgeBaseFailureRedactor {

    private final SecretResolver secretResolver;
    private final GlobalVariableResolver globalVariableResolver;

    @Inject
    public KnowledgeBaseFailureRedactor(SecretResolver secretResolver, GlobalVariableResolver globalVariableResolver) {
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
    }

    /**
     * {@code message} with the knowledge base's secrets removed by value and
     * credential shapes redacted; {@code null} for {@code null}.
     */
    public String redact(RagConfiguration config, String message) {
        if (message == null) {
            return null;
        }
        String scrubbed = message;
        Set<String> plaintexts = plaintextsOf(config);
        if (!plaintexts.isEmpty() && SecretValueScrubber.scrubAll(scrubbed, plaintexts, SecretRedactionFilter.REDACTED) instanceof String s) {
            scrubbed = s;
        }
        return SecretRedactionFilter.redact(scrubbed);
    }

    /**
     * The fallback for a caller built without this bean (unit tests): credential
     * shapes only.
     */
    public static String redact(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, String message) {
        return redactor != null ? redactor.redact(config, message) : message == null ? null : SecretRedactionFilter.redact(message);
    }

    private Set<String> plaintextsOf(RagConfiguration config) {
        Set<String> plaintexts = new HashSet<>();
        if (config == null || secretResolver == null) {
            return plaintexts;
        }
        List<Map<String, String>> parameterMaps = new ArrayList<>();
        List<Set<String>> credentialKeys = new ArrayList<>();
        if (config.getEmbeddingParameters() != null) {
            parameterMaps.add(config.getEmbeddingParameters());
            credentialKeys.add(EmbeddingParameters.credentialParameters(config.getEmbeddingProvider()));
        }
        if (config.getStoreParameters() != null) {
            parameterMaps.add(config.getStoreParameters());
            credentialKeys.add(StoreParameters.credentialParameters(config.getStoreType()));
        }
        for (int i = 0; i < parameterMaps.size(); i++) {
            Map<String, String> parameters = parameterMaps.get(i);
            Map<String, String> expanded = parameters;
            if (globalVariableResolver != null) {
                try {
                    expanded = globalVariableResolver.resolveAll(parameters);
                } catch (RuntimeException e) {
                    // Scrub what is written; a variable that cannot be expanded now could
                    // not have been resolved into the failing request either.
                }
            }
            addLiteralCredentials(expanded, credentialKeys.get(i), plaintexts);
            for (String reference : VaultHostAllowlist.referencesIn(expanded.values())) {
                try {
                    String plaintext = secretResolver.resolveValue(reference);
                    if (plaintext != null && !plaintext.equals(reference)
                            && plaintext.length() >= SecretValueScrubber.MIN_SEARCHED_SECRET_INPUT_LENGTH) {
                        plaintexts.add(plaintext);
                    }
                } catch (RuntimeException e) {
                    // Not resolvable now: then it was not in the failing request either.
                }
            }
        }
        return plaintexts;
    }

    /**
     * The literal values of the credential parameters — a key written into the
     * configuration rather than vaulted. Only the keys the provider or store takes
     * as a credential count, so a host or a model name is never scrubbed as one;
     * values shorter than {@link #MIN_LITERAL_LENGTH} (pgvector's default
     * {@code user}, {@code eddi}) would shred unrelated text and are left out.
     */
    private static void addLiteralCredentials(Map<String, String> parameters, Set<String> credentialKeys, Set<String> plaintexts) {
        for (String key : credentialKeys) {
            String value = parameters.get(key);
            if (value != null && !value.contains("${") && value.trim().length() >= MIN_LITERAL_LENGTH) {
                plaintexts.add(value.trim());
            }
        }
    }

    /** The shortest literal credential value scrubbed everywhere it occurs. */
    static final int MIN_LITERAL_LENGTH = 8;
}
