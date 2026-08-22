/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.model;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code ${connection:name}} reference, optionally tenant-qualified as
 * {@code ${connection:tenantId/name}}.
 * <p>
 * Shaped like {@code SecretReference} on purpose — an author who has learned
 * one has learned the other. What is <em>not</em> like a vault reference is how
 * it resolves: {@code ${vault:k}} is a string substitution whose answer is the
 * same for everybody, while this resolves to a credential bound to the current
 * caller and can differ between two requests one second apart. That difference
 * is why connections live above the vault rather than inside it.
 *
 * @param tenantId
 *            tenant namespace; {@code "default"} unless qualified
 * @param name
 *            the connection's {@code name} field
 */
public record ConnectionReference(String tenantId, String name) {

    /** Tenant used when a reference does not name one. */
    public static final String DEFAULT_TENANT = "default";

    /** {@code ${connection:name}} or {@code ${connection:tenant/name}}. */
    public static final String CONNECTION_PATTERN = "\\$\\{connection:(?:([^/}]+)/)?([^}]+)}";

    private static final Pattern COMPILED = Pattern.compile(CONNECTION_PATTERN);

    /** How much of an offending literal a refusal quotes back to the author. */
    private static final int MAX_QUOTED_LITERAL_CHARS = 60;

    /**
     * Whether the value carries a connection reference anywhere inside it.
     * <p>
     * {@code find}, not {@code matches}: a caller has to know a reference is
     * present before it can decide what to do, and that includes the mixed shapes —
     * {@code "Bearer ${connection:jira}"}, or two references in one value — which
     * every outbound path refuses through {@link #requireSole}. A {@code matches}
     * here would report those as carrying no reference at all, and they would go
     * out as literal text.
     */
    public static boolean contains(String value) {
        return value != null && COMPILED.matcher(value).find();
    }

    /**
     * Requires a value carrying a reference to be that reference and nothing else.
     * <p>
     * A connection supplies the WHOLE header value — name and scheme included — so
     * text around the reference is dropped rather than kept, and only the first
     * reference is ever parsed. Both losses used to happen silently: {@code "Bearer
     * ${connection:x}"} against a STATIC connection holding a bare token sent the
     * token with no scheme, and the 401 that came back named nothing.
     * <p>
     * One copy of the refusal for every outbound path, so an author who trips over
     * it in an HTTP call header reads the same sentence as one who trips over it in
     * an MCP or A2A {@code apiKey}.
     *
     * @param where
     *            what the refusal names, e.g. {@code "Header 'Authorization'"}
     * @throws IllegalArgumentException
     *             when the value carries anything besides one reference
     */
    public static void requireSole(String value, String where) {
        String candidate = value == null ? "" : value;
        if (COMPILED.matcher(candidate.trim()).matches()) {
            return;
        }
        // Bounded and redacted: what surrounds the reference is author-written text,
        // but it sits in the one field that carries credentials and may already have
        // had a ${vault:…} resolved into it by the time it reaches here.
        String around = COMPILED.matcher(candidate).replaceAll(" ").trim();
        if (around.length() > MAX_QUOTED_LITERAL_CHARS) {
            around = around.substring(0, MAX_QUOTED_LITERAL_CHARS) + "…";
        }
        throw new IllegalArgumentException(where + " puts "
                + (around.isEmpty() ? "a second ${connection:…} reference" : "the literal text '" + SecretRedactionFilter.redact(around) + "'")
                + " around its ${connection:…} reference. A connection supplies the whole value, so only the first reference is used and "
                + "everything else is dropped. Leave the reference on its own, and put any scheme in the connection's valueTemplate.");
    }

    /**
     * Parses the first reference in the value.
     *
     * @throws IllegalArgumentException
     *             when there is none
     */
    public static ConnectionReference parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Not a connection reference: null");
        }
        Matcher matcher = COMPILED.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid connection reference: " + value + ". Expected ${connection:name} "
                    + "or ${connection:tenantId/name}");
        }
        String tenant = matcher.group(1) != null ? matcher.group(1) : DEFAULT_TENANT;
        return new ConnectionReference(tenant, matcher.group(2));
    }

    /** The reference in its canonical written form. */
    public String toReferenceString() {
        return DEFAULT_TENANT.equals(tenantId) ? "${connection:" + name + "}" : "${connection:" + tenantId + "/" + name + "}";
    }
}
