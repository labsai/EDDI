/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    @Inject
    public CredentialReferenceResolver(SecretResolver secretResolver, GlobalVariableResolver globalVariableResolver) {
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
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
        String resolved = globalVariableResolver.resolveValue(template);
        resolved = secretResolver.resolveValue(resolved);
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
}
