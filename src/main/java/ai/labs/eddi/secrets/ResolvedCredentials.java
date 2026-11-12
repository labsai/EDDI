/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
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
 * {@code MIN_LENGTH - 1} characters is instead looked for as a whole token, and
 * a text that carries one is withheld entirely ({@link #WITHHELD}). Values
 * shorter still are too unspecific to look for at all. Bounded; a value that
 * falls out was resolved long enough ago that the model holding it has
 * typically been rebuilt and recorded it again.
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
     * when it carries a recorded short value as a whole token.
     */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> known = plaintexts.asMap().keySet();
        String scrubbed = known.isEmpty() ? text : RequestRedactor.redactResolvedSecrets(text, Set.copyOf(known));
        for (String value : shortPlaintexts.asMap().keySet()) {
            if (containsToken(scrubbed, value)) {
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
     * Whether {@code text} carries {@code value} not directly preceded or followed
     * by a letter or digit — so a four-digit PIN is found in "pin 1234 rejected",
     * not inside "build 912345".
     */
    public static boolean containsToken(String text, String value) {
        if (text == null || value == null || value.isEmpty()) {
            return false;
        }
        for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
            int end = at + value.length();
            boolean leftClear = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean rightClear = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftClear && rightClear) {
                return true;
            }
        }
        return false;
    }
}
