/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.ref.Reference;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 * <b>The failing client's own credentials.</b> The process-wide set is a
 * backstop, not the guarantee: what guarantees that a failure is scrubbed of
 * the credentials of the client that raised it is that the client pins them to
 * the failure itself ({@link #pin}) as it propagates —
 * {@code ChatModelRegistry} builds every chat model with its captured set, and
 * the knowledge-base paths pin what their model and store clients hold. A
 * scrubber handed that failure ({@link #scrubFailure(Throwable, String)}) then
 * removes them, however long ago the client was evicted or its reference
 * rotated: the failure is the object being scrubbed, so it is reachable for
 * exactly as long as it is needed, and nothing depends on when the client is
 * collected. A pinned value is specific to the failing client, so one shorter
 * than {@link #MIN_LENGTH} withholds the text whatever its length.
 */
@ApplicationScoped
public class ResolvedCredentials {

    /** The length from which a value is replaced wherever it occurs. */
    static final int MIN_LENGTH = 8;

    /** The length from which a shorter value is looked for as a whole token. */
    static final int MIN_SHORT_LENGTH = 4;

    /** How many exceptions of a cause chain are searched for pinned values. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** What a text carrying a short credential is replaced with. */
    public static final String WITHHELD = "[the provider's message was withheld: it repeated a credential]";

    private static final int MAX_VALUES = 10_000;

    private final Cache<String, Boolean> plaintexts = Caffeine.newBuilder().maximumSize(MAX_VALUES).build();
    private final Cache<String, Boolean> shortPlaintexts = Caffeine.newBuilder().maximumSize(MAX_VALUES).build();

    /**
     * The values each client was built with, for {@link #heldBy}: read by a caller
     * that holds the client, right after acquiring it. Weak keys, compared by
     * identity.
     */
    private final Cache<Object, Set<String>> byHolder = Caffeine.newBuilder().weakKeys().build();

    /**
     * The credentials of the client that raised each failure. Weak keys, compared
     * by identity: an entry is read only by a scrubber that holds the failure.
     */
    private final Cache<Throwable, Set<String>> byFailure = Caffeine.newBuilder().weakKeys().build();

    /**
     * Remembers {@code values} as what {@code holder} — the model or store client
     * they were resolved into — holds, for {@link #heldBy}, and in the process-wide
     * set. Without a holder, as {@link #record(Collection)}.
     */
    public void record(Object holder, Collection<String> values) {
        record(values);
        if (holder == null || values == null) {
            return;
        }
        Set<String> kept = nonEmpty(values);
        if (!kept.isEmpty()) {
            byHolder.asMap().merge(holder, kept, ResolvedCredentials::union);
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
     * What {@code holder} was built with — every length — copied, so the caller
     * keeps it for as long as it needs, independent of the client's lifetime. Call
     * it while holding the client, right after acquiring it.
     */
    public Set<String> heldBy(Object holder) {
        if (holder == null) {
            return Set.of();
        }
        try {
            Set<String> held = byHolder.getIfPresent(holder);
            return held == null ? Set.of() : held;
        } finally {
            Reference.reachabilityFence(holder);
        }
    }

    /** {@link #heldBy} for a caller built without this bean (unit tests). */
    public static Set<String> heldBy(ResolvedCredentials credentials, Object holder) {
        return credentials == null ? Set.of() : credentials.heldBy(holder);
    }

    /**
     * Ties {@code values} — the credentials of the client that raised
     * {@code failure} — to the failure, for every scrubber later handed it or an
     * exception that wraps it.
     */
    public void pin(Throwable failure, Collection<String> values) {
        if (failure == null || values == null) {
            return;
        }
        Set<String> kept = nonEmpty(values);
        if (!kept.isEmpty()) {
            byFailure.asMap().merge(failure, kept, ResolvedCredentials::union);
        }
    }

    /** {@link #pin} for a caller built without this bean (unit tests). */
    public static void pin(ResolvedCredentials credentials, Throwable failure, Collection<String> values) {
        if (credentials != null) {
            credentials.pin(failure, values);
        }
    }

    /**
     * Everything pinned to {@code failure}, its causes (up to
     * {@value #MAX_CAUSE_DEPTH}, cycle-safe) and their suppressed exceptions.
     */
    public Set<String> pinnedOn(Throwable failure) {
        Set<String> pinned = new HashSet<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            addPinned(current, pinned);
            for (Throwable suppressed : current.getSuppressed()) {
                addPinned(suppressed, pinned);
            }
            current = current.getCause();
        }
        return pinned;
    }

    /** {@link #pinnedOn} for a caller built without this bean (unit tests). */
    public static Set<String> pinnedOn(ResolvedCredentials credentials, Throwable failure) {
        return credentials == null ? Set.of() : credentials.pinnedOn(failure);
    }

    private void addPinned(Throwable failure, Set<String> pinned) {
        Set<String> values = byFailure.getIfPresent(failure);
        if (values != null) {
            pinned.addAll(values);
        }
    }

    /**
     * {@code text} with every recorded plaintext replaced — or {@link #WITHHELD}
     * when it carries a recorded short value anywhere.
     */
    public String scrub(String text) {
        return scrubFailure(null, text);
    }

    /**
     * {@link #scrub(String)}, plus the credentials pinned to {@code failure} — the
     * failure {@code text} describes — of any length.
     */
    public String scrubFailure(Throwable failure, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> known = new HashSet<>(plaintexts.asMap().keySet());
        Set<String> knownShort = new HashSet<>(shortPlaintexts.asMap().keySet());
        for (String value : failure == null ? Set.<String>of() : pinnedOn(failure)) {
            (value.length() >= MIN_LENGTH ? known : knownShort).add(value);
        }
        String scrubbed = known.isEmpty() ? text : RequestRedactor.redactResolvedSecrets(text, Set.copyOf(known));
        for (String value : knownShort) {
            if (scrubbed.contains(value)) {
                return WITHHELD;
            }
        }
        return scrubbed;
    }

    /** {@link #scrub} for a caller built without this bean (unit tests). */
    public static String scrub(ResolvedCredentials credentials, String text) {
        return credentials == null ? text : credentials.scrub(text);
    }

    /**
     * {@link #scrubFailure(Throwable, String)} for a caller built without this
     * bean.
     */
    public static String scrubFailure(ResolvedCredentials credentials, Throwable failure, String text) {
        return credentials == null ? text : credentials.scrubFailure(failure, text);
    }

    private static Set<String> nonEmpty(Collection<String> values) {
        Set<String> kept = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                kept.add(value);
            }
        }
        return Set.copyOf(kept);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return Set.copyOf(union);
    }
}