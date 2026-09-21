/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a connection document looks like to a reader who is not an
 * administrator.
 * <p>
 * {@code GET /connectionstore/connections/{id}} admits {@code eddi-editor}
 * because the Manager's picker needs a connection's name, auth type, binding
 * and header name. That is safe for a document saved under today's rules, which
 * refuse a literal in every secret-bearing field. A document written before
 * those rules can still hold one — a pasted client secret, a password, a key in
 * a header template, a token among the extra authorization parameters — and the
 * raw read handed it to every editor.
 * <p>
 * So each of those four fields keeps its value only if it passes the rule
 * save-time validation applies to it, through the model's own predicates rather
 * than a copy of them, and is otherwise replaced with {@link #REDACTED}.
 * Nothing else changes. The stored object is never touched: the copy is made
 * first, because the store may hand out a cached instance.
 */
final class ConnectionReadRedactor {

    /**
     * Carries no {@code ${}, so it can never be read as, or resolved as, a
     * reference.
     */
    static final String REDACTED = "<redacted: a literal value where a vault reference is required - an eddi-admin must re-save this connection>";

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConnectionReadRedactor() {
    }

    /**
     * A copy of {@code stored} with every legacy literal redacted, or {@code null}
     * for {@code null}.
     */
    static ConnectionConfiguration redactLegacyLiterals(ConnectionConfiguration stored) {
        if (stored == null) {
            return null;
        }
        ConnectionConfiguration copy = deepCopy(stored);
        StaticAuth staticAuth = copy.getStaticAuth();
        if (staticAuth != null) {
            staticAuth.setPasswordRef(keepIf(staticAuth.getPasswordRef(), ConnectionConfiguration::isReferenceOnly));
            staticAuth.setValueTemplate(keepIf(staticAuth.getValueTemplate(), ConnectionConfiguration::isAcceptableValueTemplate));
        }
        OAuthConfig oauth = copy.getOauth();
        if (oauth != null) {
            oauth.setClientSecret(keepIf(oauth.getClientSecret(), ConnectionConfiguration::isReferenceOnly));
            if (oauth.getExtraAuthParams() != null) {
                Map<String, String> params = new LinkedHashMap<>();
                oauth.getExtraAuthParams().forEach((key, value) -> params.put(key,
                        isAbsent(value) || ConnectionConfiguration.isAcceptableExtraAuthParam(key, value) ? value : REDACTED));
                oauth.setExtraAuthParams(params);
            }
        }
        return copy;
    }

    /**
     * An absent value holds nothing to leak, so it is left as it is rather than
     * being reported as a literal.
     */
    private static String keepIf(String value, Predicate<String> passesWriteTimeRule) {
        return isAbsent(value) || passesWriteTimeRule.test(value) ? value : REDACTED;
    }

    private static boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /**
     * A round trip through JSON rather than a hand-written copy, so a field added
     * to the model later is carried over instead of silently dropped for editors.
     */
    private static ConnectionConfiguration deepCopy(ConnectionConfiguration stored) {
        try {
            return MAPPER.readValue(MAPPER.writeValueAsBytes(stored), ConnectionConfiguration.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not copy connection '" + stored.getName() + "' for redaction", e);
        }
    }
}
