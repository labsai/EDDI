/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.model;

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

    /**
     * Whether the value carries a connection reference anywhere inside it.
     * <p>
     * {@code find}, not {@code matches}: a header value legitimately mixes literal
     * text with a reference ({@code "Bearer ${connection:jira}"} is not the shape
     * used, but {@code "${connection:jira}"} inside a longer template is), and a
     * caller needs to know a reference is present before deciding what to do.
     */
    public static boolean contains(String value) {
        return value != null && COMPILED.matcher(value).find();
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
