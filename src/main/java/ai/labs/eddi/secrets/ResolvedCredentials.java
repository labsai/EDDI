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
 * ever removes a secret. Values shorter than {@link #MIN_LENGTH} are not
 * recorded — replacing every occurrence of a short string would shred unrelated
 * text. Bounded; a value that falls out was resolved long enough ago that the
 * model holding it has typically been rebuilt and recorded it again.
 */
@ApplicationScoped
public class ResolvedCredentials {

    /** The length below which a value is too unspecific to replace everywhere. */
    static final int MIN_LENGTH = 8;

    private static final int MAX_VALUES = 10_000;

    private final Cache<String, Boolean> plaintexts = Caffeine.newBuilder().maximumSize(MAX_VALUES).build();

    /** Remembers {@code values} — plaintexts just resolved into a model client. */
    public void record(Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && value.length() >= MIN_LENGTH) {
                plaintexts.put(value, Boolean.TRUE);
            }
        }
    }

    /** {@code text} with every recorded plaintext replaced. */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> known = plaintexts.asMap().keySet();
        return known.isEmpty() ? text : RequestRedactor.redactResolvedSecrets(text, Set.copyOf(known));
    }

    /** {@link #scrub} for a caller built without this bean (unit tests). */
    public static String scrub(ResolvedCredentials credentials, String text) {
        return credentials == null ? text : credentials.scrub(text);
    }
}
