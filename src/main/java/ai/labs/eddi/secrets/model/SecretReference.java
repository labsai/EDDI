/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable reference to a secret stored in the vault. Supports two syntax
 * forms:
 * <ul>
 * <li><b>Short form:</b> {@code ${vault:keyName}} — tenant defaults to
 * {@code "default"}</li>
 * <li><b>Full form:</b> {@code ${vault:tenantId/keyName}} — explicit tenant for
 * multi-tenant</li>
 * </ul>
 * <p>
 * <b>Backward compatibility:</b> The older {@code ${eddivault:...}} prefix is
 * also accepted for configs stored before the v6.0.2 rename.
 * <p>
 * This follows the Docker image tag convention where {@code nginx} implicitly
 * means {@code docker.io/library/nginx}. Single-tenant deployments (the common
 * case) use the cleaner short form.
 * <p>
 * <b>Access model:</b> Access control is via configuration authorship — the
 * admin who writes the agent config decides which vault references to include.
 * See {@link SecretMetadata#allowedAgents()} for visibility/documentation.
 *
 * @param tenantId
 *            the tenant namespace (default: "default" for single-tenant)
 * @param keyName
 *            the secret key name
 *
 * @author ginccc
 * @since 6.0.0
 */
public record SecretReference(String tenantId, String keyName) {

    /** Default tenant ID used when none is specified in the reference. */
    public static final String DEFAULT_TENANT = "default";

    /**
     * Dual-format regex pattern that matches both vault reference forms and both
     * prefixes ({@code vault} and legacy {@code eddivault}):
     * <ul>
     * <li>{@code ${vault:keyName}} — group(1) is null, group(2) is keyName</li>
     * <li>{@code ${vault:tenantId/keyName}} — group(1) is tenantId, group(2) is
     * keyName</li>
     * <li>{@code ${eddivault:keyName}} — legacy, same groups</li>
     * </ul>
     */
    public static final String VAULT_PATTERN = "\\$\\{(?:vault|eddivault):(?:([^/}]+)/)?([^}]+)\\}";

    private static final Pattern COMPILED_PATTERN = Pattern.compile(VAULT_PATTERN);

    /**
     * Parse a vault reference string into a SecretReference. Supports both short
     * form ({@code ${vault:openaiKey}}) and full form
     * ({@code ${vault:myTenant/openaiKey}}). Also accepts the legacy
     * {@code ${eddivault:...}} prefix.
     *
     * @param reference
     *            the full reference string
     * @return the parsed SecretReference
     * @throws IllegalArgumentException
     *             if the string doesn't match the expected format
     */
    public static SecretReference parse(String reference) {
        Matcher matcher = COMPILED_PATTERN.matcher(reference);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Invalid vault reference: " + reference + ". Expected: ${vault:keyName} or ${vault:tenantId/keyName}");
        }
        String tenant = matcher.group(1) != null ? matcher.group(1) : DEFAULT_TENANT;
        String key = matcher.group(2);
        return new SecretReference(tenant, key);
    }

    /**
     * Construct the vault reference string. Uses the shortest valid form:
     * <ul>
     * <li>Default tenant → {@code ${vault:keyName}}</li>
     * <li>Custom tenant → {@code ${vault:tenantId/keyName}}</li>
     * </ul>
     */
    public String toReferenceString() {
        return DEFAULT_TENANT.equals(tenantId) ? "${vault:" + keyName + "}" : "${vault:" + tenantId + "/" + keyName + "}";
    }

    /**
     * Check if a string contains a vault reference (either new or legacy prefix).
     */
    public static boolean isVaultReference(String value) {
        return value != null && (value.contains("${vault:") || value.contains("${eddivault:"));
    }

    /**
     * Returns the compiled pattern for reuse by
     * {@link ai.labs.eddi.secrets.SecretResolver}.
     */
    public static Pattern compiledPattern() {
        return COMPILED_PATTERN;
    }
}
