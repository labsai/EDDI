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
import ai.labs.eddi.secrets.ResolvedCredentials;
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
 * then credential shapes are redacted. A credential too short to replace
 * everywhere withholds the whole message when it appears in it.
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
     * A redacted failure message, and whether redaction changed it — a caller then
     * logs the redacted text rather than the raw exception, whose message the
     * server log must not carry either.
     */
    public record Redacted(String text, boolean altered) {
    }

    /**
     * {@code message} with the knowledge base's secrets removed by value and
     * credential shapes redacted; {@code null} for {@code null}. A message carrying
     * a short credential (see {@link #redactFailure}) is withheld whole.
     */
    public String redact(RagConfiguration config, String message) {
        return redact(config, message, null).text();
    }

    /**
     * {@link #redact(RagConfiguration, String)} for a failure, naming its class in
     * the generic text that replaces a withheld message, so an operator can still
     * find the failure in the server log.
     * <p>
     * A credential of {@link #MIN_LITERAL_LENGTH} characters or more is replaced
     * wherever it occurs. A shorter one ({@link #MIN_SHORT_LENGTH} or more — a
     * seven-character database password, say) cannot be replaced everywhere without
     * mangling ordinary text, so when the message carries it as a whole token the
     * provider's text is not returned at all. Identity parameters ({@code user},
     * {@code userName}) are not secrets on their own and take no part in that.
     */
    public Redacted redactFailure(RagConfiguration config, Throwable failure) {
        return redact(config, failure == null ? null : failure.getMessage(), failure);
    }

    /**
     * {@link #redactFailure} for a caller built without this bean: credential
     * shapes only.
     */
    public static Redacted redactFailure(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, Throwable failure) {
        if (redactor != null) {
            return redactor.redactFailure(config, failure);
        }
        String message = failure == null ? null : failure.getMessage();
        String redacted = message == null ? null : SecretRedactionFilter.redact(message);
        return new Redacted(redacted, redacted != null && !redacted.equals(message));
    }

    /**
     * The fallback for a caller built without this bean (unit tests): credential
     * shapes only.
     */
    public static String redact(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, String message) {
        return redactor != null ? redactor.redact(config, message) : message == null ? null : SecretRedactionFilter.redact(message);
    }

    private Redacted redact(RagConfiguration config, String message, Throwable failure) {
        if (message == null) {
            return new Redacted(null, false);
        }
        Set<String> plaintexts = new HashSet<>();
        Set<String> shortPlaintexts = new HashSet<>();
        collectPlaintexts(config, plaintexts, shortPlaintexts);
        String scrubbed = message;
        if (!plaintexts.isEmpty() && SecretValueScrubber.scrubAll(scrubbed, plaintexts, SecretRedactionFilter.REDACTED) instanceof String s) {
            scrubbed = s;
        }
        for (String value : shortPlaintexts) {
            if (ResolvedCredentials.containsToken(scrubbed, value)) {
                return new Redacted(withheld(failure), true);
            }
        }
        scrubbed = SecretRedactionFilter.redact(scrubbed);
        return new Redacted(scrubbed, !scrubbed.equals(message));
    }

    private static String withheld(Throwable failure) {
        String kind = failure == null ? "failure" : failure.getClass().getSimpleName();
        return "The provider's error was withheld because it repeated a credential of this knowledge base (" + kind
                + "); the server log records the failure without it.";
    }

    private void collectPlaintexts(RagConfiguration config, Set<String> plaintexts, Set<String> shortPlaintexts) {
        if (config == null || secretResolver == null) {
            return;
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
            for (String key : credentialKeys.get(i)) {
                String value = expanded.get(key);
                if (value != null && !value.contains("${")) {
                    sort(value.trim(), IDENTITY_PARAMETERS.contains(key), plaintexts, shortPlaintexts);
                }
            }
            for (String reference : VaultHostAllowlist.referencesIn(expanded.values())) {
                try {
                    String plaintext = secretResolver.resolveValue(reference);
                    if (plaintext != null && !plaintext.equals(reference)) {
                        sort(plaintext, false, plaintexts, shortPlaintexts);
                    }
                } catch (RuntimeException e) {
                    // Not resolvable now: then it was not in the failing request either.
                }
            }
        }
    }

    /**
     * Files a credential value as one to replace (long enough), one whose presence
     * withholds the message (short, and a secret), or neither.
     */
    private static void sort(String value, boolean identity, Set<String> plaintexts, Set<String> shortPlaintexts) {
        if (value.length() >= MIN_LITERAL_LENGTH) {
            plaintexts.add(value);
        } else if (!identity && value.length() >= MIN_SHORT_LENGTH) {
            shortPlaintexts.add(value);
        }
    }

    /**
     * Credential parameters that name who authenticates rather than prove it:
     * pgvector's default {@code user} is {@code eddi}, also the default database
     * name, and withholding every message that mentions it would hide the cause of
     * ordinary failures.
     */
    private static final Set<String> IDENTITY_PARAMETERS = Set.of("user", "userName");

    /** The shortest credential value replaced everywhere it occurs. */
    static final int MIN_LITERAL_LENGTH = 8;

    /** The shortest credential value looked for as a whole token. */
    static final int MIN_SHORT_LENGTH = 4;
}