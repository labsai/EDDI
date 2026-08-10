/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Decides whether an agent may be deployed given the vault secrets its
 * configuration names.
 * <p>
 * Separate from {@link VaultGrantChecker} — which only answers "which
 * references are ungranted" — so the enforcement MODE lives in one place and
 * can be applied at the single deployment boundary
 * ({@code AgentFactory#deployAgent}) rather than at each of the callers that
 * reach it.
 */
@ApplicationScoped
public class VaultGrantGate {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantGate.class);

    /** Enforcement modes for {@code eddi.vault.grant-enforcement}. */
    public enum Mode {
        OFF, WARN, ENFORCE;

        /**
         * Strict parse — an unrecognised value is rejected, never defaulted.
         * {@code grant-enforcement=enforced} silently behaving as {@code warn} would
         * turn one typo into a security control that is off while appearing on.
         */
        public static Mode parseStrict(String value) {
            if (value == null || value.isBlank()) {
                return WARN;
            }
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown eddi.vault.grant-enforcement value '" + value + "'. Valid values: off, warn, enforce");
        }
    }

    private final VaultGrantChecker checker;
    private final Mode mode;

    @Inject
    public VaultGrantGate(VaultGrantChecker checker,
            @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = "warn") String grantEnforcement) {
        this.checker = checker;
        // Parsed in the constructor so an unusable value fails bean creation, i.e.
        // startup, rather than silently degrading to warn.
        this.mode = Mode.parseStrict(grantEnforcement);
    }

    /** The configured enforcement mode. */
    public Mode mode() {
        return mode;
    }

    /**
     * Whether {@code agentId} may be deployed.
     *
     * @return {@code false} only in {@code enforce} mode with a provable violation;
     *         {@code warn} logs and allows, {@code off} does not even check
     */
    public boolean mayDeploy(String agentId, Integer agentVersion) {
        if (mode == Mode.OFF || checker == null) {
            return true;
        }
        List<String> ungranted;
        try {
            ungranted = checker.findUngrantedReferences(agentId, agentVersion);
        } catch (Exception e) {
            // A check that cannot run must never block a deployment.
            LOGGER.warnf("Skipping the vault-grant check for agent '%s' v%s: %s", agentId, agentVersion, e.getMessage());
            return true;
        }
        if (ungranted.isEmpty()) {
            return true;
        }

        String message = String.format(
                "Agent '%s' v%s references vault secret(s) it is not granted: %s. SecretMetadata.allowedAgents lists "
                        + "which agents may use a secret; widen the grant or remove the reference.",
                agentId, agentVersion, ungranted);
        if (mode == Mode.ENFORCE) {
            LOGGER.error(message + " Deployment BLOCKED (eddi.vault.grant-enforcement=enforce).");
            return false;
        }
        LOGGER.warn(message + " Deployment allowed (eddi.vault.grant-enforcement=warn); set it to 'enforce' to block.");
        return true;
    }
}
