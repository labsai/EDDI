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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
     * By-value removal of the credentials the deployment's model and store clients
     * were built with — what a client still holds after its reference was rotated
     * or stopped resolving. Field-injected; null in tests built with {@code new}.
     */
    @Inject
    ResolvedCredentials resolvedCredentials;

    /**
     * A redacted failure.
     *
     * @param text
     *            what a caller may return or store
     * @param altered
     *            whether redaction changed anything it inspected, or withheld it
     * @param logText
     *            what a caller logs in place of the exception, which it never logs
     *            itself on these paths: the classes of the cause chain with each
     *            inspected message redacted — or the classes alone when the message
     *            is withheld — and a note of what was not inspected (causes beyond
     *            {@value #MAX_CAUSE_DEPTH}, suppressed exceptions)
     */
    public record Redacted(String text, boolean altered, String logText) {
    }

    /** How many exceptions of a cause chain are inspected; a cycle ends it too. */
    static final int MAX_CAUSE_DEPTH = 16;

    /**
     * {@code message} with the knowledge base's secrets removed by value and
     * credential shapes redacted; {@code null} for {@code null}. A configuration
     * whose secrets cannot all be known (see {@link #redactFailure}) never has a
     * message returned.
     */
    public String redact(RagConfiguration config, String message) {
        if (message == null) {
            return null;
        }
        var secrets = secretsOf(config);
        return secrets.withholdReason() == null ? scrub(message, secrets.plaintexts()) : withheld(null, secrets.withholdReason());
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
     * names only the classes of the cause chain, never a message. The same holds
     * when one of the configuration's vault references cannot be resolved now: the
     * failing client may hold its plaintext, and nothing here would know what to
     * remove. That holds whatever the top-level message is, a {@code null} one
     * included: the cause chain can carry the credential. Use a vault reference
     * resolving to, or a literal of, 8 characters or more to get the provider's
     * text back. Identity parameters ({@code user}, {@code userName}) are not
     * secrets on their own and take no part in that.
     * <p>
     * The credentials the failing clients were built with count as the
     * configuration's own: those passed in, and those pinned to the failure
     * ({@link ResolvedCredentials#pinnedOn}). A client built before a rotation
     * holds the old value, which a resolution now cannot return — and the same
     * withhold rule applies to it: one shorter than {@link #MIN_LITERAL_LENGTH}
     * withholds the provider text.
     * <p>
     * Otherwise every inspected message of the cause chain is redacted — those
     * secrets, then the deployment's recorded credentials, then credential shapes.
     */
    public Redacted redactFailure(RagConfiguration config, Throwable failure) {
        return redactFailure(config, failure, Set.of());
    }

    /**
     * {@link #redactFailure(RagConfiguration, Throwable)} with
     * {@code clientCredentials}, what the clients the failing operation used were
     * built with ({@link ResolvedCredentials#heldBy}), passed by the caller that
     * held them.
     */
    public Redacted redactFailure(RagConfiguration config, Throwable failure, Collection<String> clientCredentials) {
        var secrets = secretsOf(config);
        Set<String> plaintexts = new HashSet<>(secrets.plaintexts());
        String reason = secrets.withholdReason();
        Set<String> held = new HashSet<>(ResolvedCredentials.pinnedOn(resolvedCredentials, failure));
        if (clientCredentials != null) {
            held.addAll(clientCredentials);
        }
        for (String value : held) {
            if (value.length() >= MIN_LITERAL_LENGTH) {
                plaintexts.add(value);
            } else if (reason == null) {
                reason = SHORT_CREDENTIAL;
            }
        }
        if (reason != null) {
            return new Redacted(withheld(failure, reason), true, describeChain(failure, null));
        }
        return redactChain(failure, message -> scrub(message, plaintexts));
    }

    /**
     * {@link #redactFailure} for a caller built without this bean: credential
     * shapes only.
     */
    public static Redacted redactFailure(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, Throwable failure) {
        return redactFailure(redactor, config, failure, Set.of());
    }

    /**
     * {@link #redactFailure(RagConfiguration, Throwable, Collection)}, null-safe.
     */
    public static Redacted redactFailure(KnowledgeBaseFailureRedactor redactor, RagConfiguration config, Throwable failure,
                                         Collection<String> clientCredentials) {
        if (redactor != null) {
            return redactor.redactFailure(config, failure, clientCredentials);
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
        boolean[] altered = {text != null && !text.equals(top)};
        String log = describeChain(failure, message -> {
            String redacted = redactor.apply(message);
            altered[0] |= !redacted.equals(message);
            return redacted;
        });
        return new Redacted(text, altered[0], log);
    }

    /**
     * The cause chain as {@code Class: message <- caused by Class: message}, each
     * message passed through {@code redactor} — or left out when it is
     * {@code null}. Suppressed exceptions and causes beyond the cap are never
     * inspected, so their text is never included: the line says they were left out.
     */
    private static String describeChain(Throwable failure, UnaryOperator<String> redactor) {
        if (failure == null) {
            return "failure";
        }
        var log = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int suppressed = 0;
        Throwable current = failure;
        int depth = 0;
        for (; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (!log.isEmpty()) {
                log.append(" <- caused by ");
            }
            log.append(current.getClass().getName());
            String message = current.getMessage();
            if (redactor != null && message != null) {
                log.append(": ").append(redactor.apply(message));
            }
            suppressed += current.getSuppressed().length;
            current = current.getCause();
        }
        if (current != null && !seen.contains(current)) {
            log.append(" <- further causes not inspected");
        }
        if (suppressed > 0) {
            log.append(" (").append(suppressed).append(" suppressed exception(s) not inspected)");
        }
        if (redactor == null) {
            log.append(" (messages withheld)");
        }
        return log.toString();
    }

    private String scrub(String message, Set<String> plaintexts) {
        String scrubbed = message;
        if (!plaintexts.isEmpty() && SecretValueScrubber.scrubAll(scrubbed, plaintexts, SecretRedactionFilter.REDACTED) instanceof String s) {
            scrubbed = s;
        }
        scrubbed = ResolvedCredentials.scrub(resolvedCredentials, scrubbed);
        return SecretRedactionFilter.redact(scrubbed);
    }

    /**
     * @param withholdReason
     *            why no provider text may be returned for this configuration, or
     *            {@code null} when its secrets are all known and long enough
     */
    private record Secrets(Set<String> plaintexts, String withholdReason) {
    }

    private static final String SHORT_CREDENTIAL = "this knowledge base has a credential too short to remove from it";
    private static final String UNRESOLVABLE_REFERENCE = "a vault reference of this knowledge base cannot be resolved now,"
            + " so its secret cannot be removed from it";

    private Secrets secretsOf(RagConfiguration config) {
        Set<String> plaintexts = new HashSet<>();
        Set<String> shortPlaintexts = new HashSet<>();
        boolean unresolvable = collectPlaintexts(config, plaintexts, shortPlaintexts);
        String reason = !shortPlaintexts.isEmpty() ? SHORT_CREDENTIAL : unresolvable ? UNRESOLVABLE_REFERENCE : null;
        return new Secrets(plaintexts, reason);
    }

    private static String withheld(Throwable failure, String reason) {
        String kind = failure == null ? "failure" : failure.getClass().getSimpleName();
        String remedy = SHORT_CREDENTIAL.equals(reason) ? "use a vault reference or a credential of 8 characters or more. " : "";
        return "The provider's error was withheld because " + reason + " (" + kind + "); " + remedy
                + "The server log records the failure without it.";
    }
    /** @return whether a vault reference could not be resolved now */
    private boolean collectPlaintexts(RagConfiguration config, Set<String> plaintexts, Set<String> shortPlaintexts) {
        if (config == null || secretResolver == null) {
            return false;
        }
        boolean unresolvable = false;
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
                    } else {
                        unresolvable = true;
                    }
                } catch (RuntimeException e) {
                    // Not resolvable now — but the failing client may have been built while
                    // it was, and hold its plaintext: fail closed.
                    unresolvable = true;
                }
            }
        }
        return unresolvable;
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
