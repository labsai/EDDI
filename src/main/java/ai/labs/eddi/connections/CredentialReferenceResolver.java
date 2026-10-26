/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Expands {@code ${vars:…}} then {@code ${vault:…}} inside one credential
 * value, and refuses anything that did not fully resolve.
 *
 * <h3>Why this is one class and not three copies</h3> There were three: one in
 * {@code ConnectionResolver}, one in {@code OAuthTokenService}, one in
 * {@code RestConnectionAuthorization}. Each checked a different subset of the
 * reference forms, so the same misconfigured connection behaved three different
 * ways depending on which path reached it — and the copy on the refresh path
 * missed {@code ${vars:}} entirely.
 * <p>
 * That miss is not cosmetic. An unresolved {@code ${vars:client-secret}} is
 * sent to the token endpoint AS the client secret; the provider answers
 * {@code invalid_client}, which the client maps to {@code GRANT_UNUSABLE}, and
 * the grant is marked {@code REFRESH_FAILED} — <em>terminally</em>. Every user
 * of that connection is logged out, permanently, by a typo in a global
 * variable, and no message anywhere names the variable.
 *
 * <h3>Order</h3> Global variables first, then the vault, matching every other
 * resolution chain in the codebase: a variable may expand to a vault reference,
 * and the reverse is never intended.
 */
@ApplicationScoped
public class CredentialReferenceResolver {

    /**
     * A reference that survived resolution — meaning the key or variable behind it
     * does not exist. Every form is checked, not just the vault one: a
     * {@code ${vars:}} that did not resolve fails identically and just as
     * expensively.
     */
    private static final Pattern UNRESOLVED_REFERENCE = Pattern.compile("\\$\\{(vault|eddivault|vars):[^}]*}");

    private final SecretResolver secretResolver;
    private final GlobalVariableResolver globalVariableResolver;
    private final VaultHostAllowlist vaultHostAllowlist;

    @Inject
    public CredentialReferenceResolver(SecretResolver secretResolver, GlobalVariableResolver globalVariableResolver,
            VaultHostAllowlist vaultHostAllowlist) {
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
        this.vaultHostAllowlist = vaultHostAllowlist;
    }

    /**
     * Without a destination check — for the save-time helpers and for tests that do
     * not exercise one.
     */
    public CredentialReferenceResolver(SecretResolver secretResolver, GlobalVariableResolver globalVariableResolver) {
        this(secretResolver, globalVariableResolver, null);
    }

    /**
     * Resolves a credential template, or refuses.
     *
     * @param template
     *            the configured value, possibly carrying references
     * @param connectionName
     *            named in the failure message, because "a credential did not
     *            resolve" is not actionable
     * @param what
     *            which field this is ("client secret", "credential"), for the same
     *            reason
     * @throws ConnectionException
     *             with {@code INVALID_CONFIGURATION} when the result is empty or
     *             still carries a reference. Never returns a partially resolved
     *             value: sending one produces a provider rejection that looks like
     *             a revoked grant.
     */
    public String resolveRequired(String template, String connectionName, String what) {
        return resolveRequired(template, connectionName, what, null);
    }

    /**
     * {@link #resolveRequired(String, String, String)} for a credential that is
     * about to be sent to {@code destination}.
     * <p>
     * A connection's {@code baseUrlAllowlist} says where the <em>connection</em>
     * may be sent; the {@code ${vault:…}} secrets behind it carry their own
     * {@code allowedHosts}, set by whoever vaulted them. Both must hold: without
     * this check a connection was a way around the secret's own policy, because the
     * reference became plaintext here and the caller only ever saw the plaintext.
     * The references are checked after {@code ${vars:…}} expansion, so one a
     * variable points at is checked too, and before anything is decrypted.
     *
     * @param destination
     *            the request's target, or {@code null} when the value is not sent
     *            anywhere this caller knows
     * @throws ConnectionException
     *             with {@code TARGET_NOT_ALLOWED} when a secret's
     *             {@code allowedHosts} does not admit {@code destination}
     */
    public String resolveRequired(String template, String connectionName, String what, URI destination) {
        String expanded = globalVariableResolver.resolveValue(template);
        if (template != null && !template.equals(expanded) && !ConnectionConfiguration.isAcceptableValueTemplate(expanded)) {
            // A ${vars:} in a header template may stand for a vault reference or for
            // the scaffolding around one ("Bearer "). It may not stand for the
            // credential itself: a global variable is readable by every editor, and
            // expanding one into the header would carry a plaintext key the vault never
            // saw. The expanded template is held to the same rule the author would face
            // writing it literally.
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The " + what + " for connection '" + connectionName + "' expands a ${vars:…} reference into something that is not a "
                            + "${vault:…} reference or short scaffolding around one. A global variable is readable by every editor, so a "
                            + "credential may only live in the vault: store it with PUT /secretstore/secrets/{tenantId}/{keyName} and let "
                            + "the variable hold the ${vault:…} reference.");
        }
        requireDestinationAllowed(expanded, destination, connectionName, what);
        String resolved = secretResolver.resolveValue(expanded);
        if (resolved == null || resolved.isBlank()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The " + what + " for connection '" + connectionName + "' resolved to an empty value. Check that the referenced vault key "
                            + "or global variable exists.");
        }
        if (UNRESOLVED_REFERENCE.matcher(resolved).find()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The " + what + " for connection '" + connectionName + "' has a reference that did not resolve. The vault key or global "
                            + "variable is missing, or the vault is inactive. Refusing rather than sending the literal text, which the "
                            + "provider would reject as a bad credential and which is indistinguishable from a revoked grant.");
        }
        return resolved;
    }

    private void requireDestinationAllowed(String expanded, URI destination, String connectionName, String what) {
        if (destination == null || vaultHostAllowlist == null || expanded == null) {
            return;
        }
        try {
            vaultHostAllowlist.requireAllowed(VaultHostAllowlist.referencesIn(List.of(expanded)), destination.toString(),
                    "The " + what + " for connection '" + connectionName + "'");
        } catch (VaultHostAllowlist.HostNotAllowedException e) {
            throw new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED, e.getMessage(), e);
        }
    }

    /**
     * {@link #resolveRequired} for a field whose whole value must be a vault
     * reference — {@code oauth.clientSecret}, {@code staticAuth.passwordRef}.
     * <p>
     * Save-time validation accepts {@code ${vars:x}} in those fields, so that a
     * variable can carry the reference for several connections. It is accepted on
     * one condition: the variable holds a {@code ${vault:…}} reference. Without the
     * condition the variable store, which every editor can read, is a second place
     * a client secret can live in plaintext, outside the vault, export scrubbing
     * and the grant check. The condition is checked here, at resolution, because
     * the variable can be created or changed after the connection was saved.
     *
     * @throws ConnectionException
     *             with {@code INVALID_CONFIGURATION} when the field, after
     *             {@code ${vars:…}} expansion, is not exactly one vault reference
     */
    public String resolveVaultBacked(String template, String connectionName, String what) {
        return resolveVaultBacked(template, connectionName, what, null);
    }

    /**
     * {@link #resolveVaultBacked(String, String, String)} for a value about to be
     * sent to {@code destination}; see
     * {@link #resolveRequired(String, String, String, URI)}.
     */
    public String resolveVaultBacked(String template, String connectionName, String what, URI destination) {
        if (template == null || template.isBlank()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The " + what + " for connection '" + connectionName + "' is empty. Re-save the connection with a ${vault:…} reference.");
        }
        String expanded = globalVariableResolver.resolveValue(template);
        if (!ConnectionConfiguration.isVaultReferenceOnly(expanded)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The " + what + " for connection '" + connectionName + "' " + notVaultBacked(template, expanded));
        }
        return resolveRequired(expanded, connectionName, what, destination);
    }

    /**
     * The save-time half of {@link #resolveVaultBacked}: refuses a
     * {@code ${vars:x}} whose variable exists and holds anything but a vault
     * reference. A variable that does not exist yet is let through — the connection
     * may be saved before the variable, and {@link #resolveVaultBacked} refuses the
     * connection at use until the variable is right.
     *
     * @throws IllegalArgumentException
     *             with the message the 400 carries
     */
    public void requireVaultBacked(String value, String field) {
        if (value == null || !value.contains("${vars:")) {
            return;
        }
        String expanded = globalVariableResolver.resolveValue(value);
        if (expanded.contains("${vars:") || ConnectionConfiguration.isVaultReferenceOnly(expanded)) {
            return;
        }
        throw new IllegalArgumentException(field + " " + notVaultBacked(value, expanded));
    }

    /**
     * The save-time half of the header-template rule in {@link #resolveRequired}:
     * refuses a {@code staticAuth.valueTemplate} whose variables, expanded now,
     * leave something {@link ConnectionConfiguration#isAcceptableValueTemplate}
     * would not accept — a key where only a reference or scaffolding may stand. A
     * variable that does not exist yet is let through, as in
     * {@link #requireVaultBacked}; the use-time check closes that.
     *
     * @throws IllegalArgumentException
     *             with the message the 400 carries
     */
    public void requireAcceptableTemplate(String template, String field) {
        if (template == null || !template.contains("${vars:")) {
            return;
        }
        String expanded = globalVariableResolver.resolveValue(template);
        if (expanded.contains("${vars:") || ConnectionConfiguration.isAcceptableValueTemplate(expanded)) {
            return;
        }
        throw new IllegalArgumentException(field + " expands a ${vars:…} reference into something that is not a ${vault:…} reference or "
                + "short scaffolding around one. A global variable is readable by every editor, so a credential may only live in the "
                + "vault: store it with PUT /secretstore/secrets/{tenantId}/{keyName} and let the variable hold the ${vault:…} reference.");
    }

    /** Never quotes the expansion: it may be the very credential this refuses. */
    private static String notVaultBacked(String template, String expanded) {
        String reason = expanded.contains("${vault:") || expanded.contains("${eddivault:")
                ? "expands " + template + " into more than one vault reference"
                : "expands " + template + " into a value that is not a ${vault:…} reference";
        return reason + ". A global variable is readable by every editor, so it may only point at the vault: store the credential with "
                + "PUT /secretstore/secrets/{tenantId}/{keyName} and set the variable to the ${vault:…} reference it answers with.";
    }
}
