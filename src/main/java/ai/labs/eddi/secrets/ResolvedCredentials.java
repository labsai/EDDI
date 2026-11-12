/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plaintexts of the vault secrets handed to model clients, so that an error
 * those clients raise can be scrubbed of them by value before it reaches the
 * caller.
 * <p>
 * The httpcall path carries the secrets it resolved for one request to
 * {@link RequestRedactor#redactResolvedSecrets} itself. A chat model is built
 * once and cached, and its failures surface far from the build — in the task
 * error digest of {@code LifecycleManager} and in {@code LlmFallbackHandler}'s
 * {@code llm:error:<taskId>} — so {@code ChatModelRegistry} records here what
 * it resolved, and those paths scrub with the same redactor. Pattern-only
 * redaction misses a key without a recognisable prefix, which is exactly what a
 * provider echoing a gateway's {@code customHeaders} or
 * {@code customQueryParams} value sends back.
 * <p>
 * Process-wide by design: every recorded value is a credential some model of
 * this deployment holds anyway, and scrubbing one from an unrelated error only
 * ever removes a secret.
 * <p>
 * <b>Short values.</b> A value of {@link #MIN_LENGTH} characters or more is
 * replaced wherever it occurs. Replacing a shorter one everywhere would shred
 * unrelated text, so a value of {@link #MIN_SHORT_LENGTH} to
 * {@code MIN_LENGTH - 1} characters is instead looked for anywhere in the text,
 * joined to other characters or not, and a text that carries one is withheld
 * entirely ({@link #WITHHELD}). Values under {@code MIN_SHORT_LENGTH}
 * characters <em>cannot be covered</em> here: the set is process-wide, and
 * withholding every error that contains some two- or three-character string
 * would hide the cause of nearly every failure. Use credentials of 8 characters
 * or more.
 * <p>
 * <b>Retention.</b> A value recorded against the client that holds it
 * ({@link #record(Object, Collection)}) is kept for exactly as long as that
 * client is reachable — while it is cached, and while any request still running
 * on it after an eviction or rotation holds it — however many other values are
 * recorded meanwhile: the holder is a weak key, compared by identity. Values
 * recorded without a holder (a build that has not produced its client yet) go
 * to a bounded set.
 */
@ApplicationScoped
public class ResolvedCredentials {

    /** The length from which a value is replaced wherever it occurs. */
    static final int MIN_LENGTH = 8;

    /** The length from which a shorter value is looked for as a whole token. */
    static final int MIN_SHORT_LENGTH = 4;

    /** What a text carrying a short credential is replaced with. */
    public static final String WITHHELD = "[the provider's message was withheld: it repeated a credential]";

    private static final int MAX_VALUES = 10_000;

    private final Cache<String, Boolean> plaintexts = Caffeine.newBuilder().maximumSize(MAX_VALUES).build();
    private final Cache<String, Boolean> shortPlaintexts = Caffeine.newBuilder().maximumSize(MAX_VALUES).build();

    /**
     * The values each live client was built with. Weak keys: an entry leaves when
     * its client becomes unreachable, never because others were recorded.
     */
    private final Cache<Object, Set<String>> byHolder = Caffeine.newBuilder().weakKeys().build();

    /**
     * Remembers {@code values} for as long as {@code holder} — the model or store
     * client they were resolved into — is reachable. Without a holder, as
     * {@link #record(Collection)}.
     */
    public void record(Object holder, Collection<String> values) {
        if (holder == null) {
            record(values);
            return;
        }
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> kept = new HashSet<>();
        for (String value : values) {
            if (value != null && value.length() >= MIN_SHORT_LENGTH) {
                kept.add(value);
            }
        }
        if (!kept.isEmpty()) {
            byHolder.asMap().merge(holder, Set.copyOf(kept), (a, b) -> {
                Set<String> union = new HashSet<>(a);
                union.addAll(b);
                return Set.copyOf(union);
            });
        }
    }

    /** Remembers {@code values} — plaintexts just resolved into a model client. */
    public void record(Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (value.length() >= MIN_LENGTH) {
                plaintexts.put(value, Boolean.TRUE);
            } else if (value.length() >= MIN_SHORT_LENGTH) {
                shortPlaintexts.put(value, Boolean.TRUE);
            }
        }
    }

    /**
     * {@code text} with every recorded plaintext replaced — or {@link #WITHHELD}
     * when it carries a recorded short value anywhere.
     */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> known = new HashSet<>(plaintexts.asMap().keySet());
        Set<String> knownShort = new HashSet<>(shortPlaintexts.asMap().keySet());
        for (Set<String> held : byHolder.asMap().values()) {
            for (String value : held) {
                (value.length() >= MIN_LENGTH ? known : knownShort).add(value);
            }
        }
        String scrubbed = known.isEmpty() ? text : RequestRedactor.redactResolvedSecrets(text, Set.copyOf(known));
        for (String value : knownShort) {
            if (scrubbed.contains(value)) {
                return WITHHELD;
            }
        }
        return scrubbed;
    }

    /**
     * The values a resolution substituted into {@code keys}: each credential
     * parameter that held a vault reference before and differs after — what the
     * client built from {@code after} holds.
     */
    public static List<String> substituted(Map<String, String> before, Map<String, String> after, Set<String> keys) {
        List<String> values = new ArrayList<>();
        if (before == null || after == null || keys == null) {
            return values;
        }
        for (String key : keys) {
            String reference = before.get(key);
            String value = after.get(key);
            if (reference != null && value != null && SecretReference.isVaultReference(reference) && !value.equals(reference)) {
                values.add(value);
            }
        }
        return values;
    }

    /** {@link #scrub} for a caller built without this bean (unit tests). */
    public static String scrub(ResolvedCredentials credentials, String text) {
        return credentials == null ? text : credentials.scrub(text);
    }
}
