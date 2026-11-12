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
import java.util.function.UnaryOperator;

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
 * then credential shapes are redacted. A configuration holding a credential too
 * short to replace by value never has its provider text returned.
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
     * A redacted failure: the text a caller may return or store, whether redaction
     * changed anything anywhere in the cause chain, and the text to log in place of
     * the exception when it did.
     *
     * @param logText
     *            what a caller logs instead of the exception object when
     *            {@code altered}: the exception classes alone when the short-
     *            credential fail-safe applies, otherwise the redacted message of
     *            every exception in the cause chain
     */
    public record Redacted(String text, boolean altered, String logText) {
    }

    /** How deep a cause chain is walked; a cycle ends it too. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * {@code message} with the knowledge base's secrets removed by value and
     * credential shapes redacted; {@code null} for {@code null}. A configuration
     * with a short credential (see {@link #redactFailure}) never has a message
     * returned.
     */
    public String redact(RagConfiguration config, String message) {
        if (message == null) {
            return null;
        }
        var secrets = secretsOf(config);
        return secrets.shortPlaintexts().isEmpty() ? scrub(message, secrets.plaintexts()) : withheld(null);
    }

    /**
     * {@link #redact(RagConfiguration, String)} for a failure, naming its class in
     * the generic text that replaces a withheld message, so an operator can still
     * find the failure in the server log.
     * <p>
     * A credential of {@link #MIN_LITERAL_LENGTH} characters or more is replaced
     * wherever it occurs. A shorter one (a three- or seven-character database
     * password, say) cannot be replaced everywhere without mangling ordinary text,
     * nor found reliably — it may be echoed joined to other text — so a
     * configuration with one never has its provider text returned: every failure
     * becomes the generic message naming the exception class, and the log text
     * names only the classes of the cause chain, never a message. That holds
     * whatever the top-level message is, a {@code null} one included: the cause
     * chain can carry the credential. Use a vault reference resolving to, or a
     * literal of, 8 characters or more to get the provider's text back. Identity
     * parameters ({@code user}, {@code userName}) are not secrets on their own and
     * take no part in that.
     * <p>
     * Otherwise every message of the cause chain is redacted, and {@code altered}
     * says whether any of them changed: the caller then logs {@code logText} rather
     * than the exception, whose cause messages would carry what was removed.
     */
    public Redacted redactFailure(RagConfiguration config, Throwable failure) {
        var secrets = secretsOf(config);
        if (!secrets.shortPlaintexts().isEmpty()) {
            return new Redacted(withheld(failure), true, classesOf(failure));
        }
        return redactChain(failure, message -> scrub(message, secrets.plaintexts()));
    }

    /**
     * {@link #redactFailure} for a caller built without this bean: credential
     * shapes only.
     */
    public static Redacted redactFailure(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, Throwable failure) {
        if (redactor != null) {
            return redactor.redactFailure(config, failure);
        }
        return redactChain(failure, SecretRedactionFilter::redact);
    }

    /**
     * The fallback for a caller built without this bean (unit tests): credential
     * shapes only.
     */
    public static String redact(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, String message) {
        return redactor != null ? redactor.redact(config, message) : message == null ? null : SecretRedactionFilter.redact(message);
    }

    private static Redacted redactChain(Throwable failure, UnaryOperator<String> redactor) {
        if (failure == null) {
            return new Redacted(null, false, null);
        }
        String top = failure.getMessage();
        String text = top == null ? null : redactor.apply(top);
        boolean altered = text != null && !text.equals(top);
        var log = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            String message = current.getMessage();
            String redacted = message == null ? null : redactor.apply(message);
            altered |= redacted != null && !redacted.equals(message);
            if (!log.isEmpty()) {
                log.append(" <- caused by ");
            }
            log.append(current.getClass().getName());
            if (redacted != null) {
                log.append(": ").append(redacted);
            }
            current = current.getCause();
        }
        return new Redacted(text, altered, log.toString());
    }

    private static String classesOf(Throwable failure) {
        var classes = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (!classes.isEmpty()) {
                classes.append(" <- caused by ");
            }
            classes.append(current.getClass().getName());
            current = current.getCause();
        }
        return classes.isEmpty() ? "failure" : classes.append(" (messages withheld: the knowledge base has a short credential)").toString();
    }

    private static String scrub(String message, Set<String> plaintexts) {
        String scrubbed = message;
        if (!plaintexts.isEmpty() && SecretValueScrubber.scrubAll(scrubbed, plaintexts, SecretRedactionFilter.REDACTED) instanceof String s) {
            scrubbed = s;
        }
        return SecretRedactionFilter.redact(scrubbed);
    }

    private record Secrets(Set<String> plaintexts, Set<String> shortPlaintexts) {
    }

    private Secrets secretsOf(RagConfiguration config) {
        Set<String> plaintexts = new HashSet<>();
        Set<String> shortPlaintexts = new HashSet<>();
        collectPlaintexts(config, plaintexts, shortPlaintexts);
        return new Secrets(plaintexts, shortPlaintexts);
    }

    private static String withheld(Throwable failure) {
        String kind = failure == null ? "failure" : failure.getClass().getSimpleName();
        return "The provider's error was withheld because this knowledge base has a credential too short to remove from it (" + kind
                + "); use a vault reference or a credential of 8 characters or more. The server log records the failure without it.";
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
        } else if (!identity && !value.isEmpty()) {
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

}
