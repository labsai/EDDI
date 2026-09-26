/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

/**
 * The vault key a {@code scope: "secret"} property of a conversation is stored
 * under — {@code <agentId>.<conversationId>.<property>}, default tenant.
 * <p>
 * The single definition, shared by the writer ({@code SecretPropertyVault}),
 * the reference guard that must recognise what the writer wrote
 * ({@code ConfigReferenceGuard}) and save-time validation of property names.
 * <p>
 * One entry per conversation, not per agent: the vault write is an upsert, and
 * a key shared by every conversation of an agent let the last user to enter a
 * value supply the credential for everyone. The tenant is not a parameter — it
 * used to come from a {@code tenantId} conversation property, which a client
 * can set.
 */
public final class AutoVaultReference {

    private AutoVaultReference() {
    }

    /**
     * @return the reference, or {@code null} when any part is missing or cannot be
     *         embedded in a reference
     */
    public static SecretReference of(String agentId, String conversationId, String propertyName) {
        if (!isEmbeddable(agentId) || !isEmbeddable(conversationId) || !isEmbeddable(propertyName)) {
            return null;
        }
        return new SecretReference(SecretReference.DEFAULT_TENANT, agentId + "." + conversationId + "." + propertyName);
    }

    /**
     * Whether {@code part} can sit inside {@code ${vault:<key>}} without changing
     * what the reference parses to: a {@code /} would re-parse as a tenant
     * separator, a {@code }} would end the reference early, and a {@code {} or
     * {@code $} would start another one.
     */
    public static boolean isEmbeddable(String part) {
        if (part == null || part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '/' || c == '{' || c == '}' || c == '$' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
