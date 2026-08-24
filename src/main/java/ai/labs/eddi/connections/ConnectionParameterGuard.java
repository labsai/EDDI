/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import java.util.Map;

/**
 * Refuses a {@code ${connection:…}} in a model, embedding or vector-store
 * parameter map.
 * <p>
 * Connections resolve to a <em>header</em> — a name and a value — because that
 * is what an outbound HTTP call needs, and it is what lets one connection model
 * describe {@code Authorization: Bearer …} and {@code X-Api-Key: …} alike. A
 * provider builder wants the bare credential instead, and there is no honest
 * way to derive one from the header form: stripping a scheme prefix off a
 * static template is a guess, and a guess that is wrong for one provider out of
 * eleven produces an authentication failure with no visible cause.
 * <p>
 * These three caches are also keyed on <em>unresolved</em> parameters by design
 * — the property that makes deploy-time vault-grant enforcement sound — so a
 * per-request credential is incoherent there even once the format question is
 * answered.
 * <p>
 * So a reference is refused loudly rather than sent as literal text and
 * rejected by the provider as an opaque 401. {@code ${vault:…}} is the
 * supported form and does everything a {@code SERVICE}-bound connection would
 * do for these paths.
 */
public final class ConnectionParameterGuard {

    private ConnectionParameterGuard() {
    }

    /**
     * @throws IllegalArgumentException
     *             if any parameter carries a reference
     */
    public static void rejectConnectionReferences(Map<String, ?> parameters) {
        if (parameters == null) {
            return;
        }
        for (var entry : parameters.entrySet()) {
            if (entry.getValue() instanceof String value && ConnectionResolver.containsReference(value)) {
                throw new IllegalArgumentException("Parameter '" + entry.getKey() + "' uses a ${connection:…} reference, which is not supported "
                        + "for language models, embedding models or vector stores: a connection resolves to an HTTP header, while these "
                        + "builders need a bare credential. Use ${vault:…} instead.");
            }
        }
    }
}
