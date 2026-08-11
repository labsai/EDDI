/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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

    /**
     * The mode applied when the property is absent or blank.
     * <p>
     * Deliberately the SAME value the bundled {@code application.properties} ships.
     * When the two disagree — a property saying {@code enforce} and a code fallback
     * saying {@code warn} — an external configuration that omits or blanks the key
     * silently downgrades the control while every visible sign still says it is on.
     * Fail closed, and keep the two definitions in one place.
     */
    public static final String DEFAULT_MODE_NAME = "enforce";

    /** Enforcement modes for {@code eddi.vault.grant-enforcement}. */
    public enum Mode {
        OFF, WARN, ENFORCE;

        /**
         * Strict parse — an unrecognised value is rejected, never defaulted.
         * {@code grant-enforcement=enforced} silently behaving as {@code warn} would
         * turn one typo into a security control that is off while appearing on.
         * <p>
         * Absent or blank resolves to {@link #DEFAULT_MODE_NAME}, not to a weaker mode:
         * an operator who blanks the value gets the shipped behaviour, never a quieter
         * one. Turning enforcement down has to be explicit.
         */
        public static Mode parseStrict(String value) {
            if (value == null || value.isBlank()) {
                return valueOf(DEFAULT_MODE_NAME.toUpperCase());
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
            @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = DEFAULT_MODE_NAME) String grantEnforcement) {
        this.checker = checker;
        // Parsed in the constructor so an unusable value fails bean creation, i.e.
        // startup, rather than silently degrading.
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
            LOGGER.warnf("Skipping the vault-grant check for agent '%s' v%s: %s", sanitize(agentId), agentVersion,
                    sanitize(e.getMessage()));
            return true;
        }
        if (ungranted.isEmpty()) {
            return true;
        }

        String message = String.format(
                "Agent '%s' v%s references vault secret(s) it is not granted: %s. SecretMetadata.allowedAgents lists "
                        + "which agents may use a secret; widen the grant or remove the reference.",
                sanitize(agentId), agentVersion, sanitize(String.valueOf(ungranted)));
        if (mode == Mode.ENFORCE) {
            LOGGER.error(message + " Deployment BLOCKED (eddi.vault.grant-enforcement=enforce).");
            return false;
        }
        LOGGER.warn(message + " Deployment allowed (eddi.vault.grant-enforcement=warn); set it to 'enforce' to block.");
        return true;
    }
}
