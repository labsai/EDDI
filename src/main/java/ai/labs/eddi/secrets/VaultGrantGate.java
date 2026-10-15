/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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
     * What the grant check found for one agent version.
     *
     * @param mode
     *            the enforcement mode it was evaluated under
     * @param ungranted
     *            the references the agent names but is not granted; empty when
     *            fully granted or not {@link #checked}
     * @param checked
     *            false when the check did not run — mode {@code off}, or it failed
     *            — in which case the deploy is let through
     */
    public record GrantCheck(Mode mode, List<String> ungranted, boolean checked) {

        public GrantCheck {
            ungranted = ungranted == null ? List.of() : List.copyOf(ungranted);
        }

        /** Whether the deployment is refused: enforce mode with a violation. */
        public boolean blocked() {
            return mode == Mode.ENFORCE && !ungranted.isEmpty();
        }

        /**
         * The structured reason a refused deployment reports, naming every secret and
         * the grant call that fixes it. Never carries a secret value: the references
         * are names.
         */
        public DeploymentFailure toFailure(String agentId, Integer agentVersion) {
            List<DeploymentFailure.SecretRef> secrets = new ArrayList<>();
            List<String> endpoints = new ArrayList<>();
            for (String reference : ungranted) {
                SecretReference parsed = parseVaultReference(reference);
                if (parsed == null) {
                    secrets.add(new DeploymentFailure.SecretRef(null, null, reference));
                    continue;
                }
                secrets.add(new DeploymentFailure.SecretRef(parsed.tenantId(), parsed.keyName(), reference));
                endpoints.add(appendEndpoint(parsed, agentId));
            }
            return new DeploymentFailure(DeploymentFailure.VAULT_GRANT_MISSING, describe(agentId, agentVersion, secrets, endpoints), secrets,
                    endpoints.isEmpty() ? null : new DeploymentFailure.Fix(agentId, endpoints, true));
        }
    }

    /**
     * The grant check, without logging or deciding anything — what a preflight
     * shows before a deploy is attempted. A check that cannot run reports
     * {@code checked=false} and no violations, exactly as {@link #mayDeploy} then
     * lets the deployment through: a preview must not predict a refusal the
     * deployment would not make.
     */
    public GrantCheck check(String agentId, Integer agentVersion) {
        if (mode == Mode.OFF || checker == null) {
            return new GrantCheck(mode, List.of(), false);
        }
        try {
            return new GrantCheck(mode, checker.findUngrantedReferences(agentId, agentVersion), true);
        } catch (Exception e) {
            // A check that cannot run must never block a deployment.
            LOGGER.warnf("Skipping the vault-grant check for agent '%s' v%s: %s", sanitize(agentId), agentVersion,
                    sanitize(e.getMessage()));
            return new GrantCheck(mode, List.of(), false);
        }
    }

    /**
     * The check the deployment boundary runs: {@link #check} plus the log line an
     * operator with server access reads. The returned {@link GrantCheck} carries
     * the same verdict to the API, through {@link GrantCheck#toFailure}.
     */
    public GrantCheck checkForDeployment(String agentId, Integer agentVersion) {
        GrantCheck result = check(agentId, agentVersion);
        if (result.ungranted().isEmpty()) {
            return result;
        }
        String message = sanitize(result.toFailure(agentId, agentVersion).message());
        if (result.blocked()) {
            LOGGER.error(message + " Deployment BLOCKED (eddi.vault.grant-enforcement=enforce).");
        } else {
            LOGGER.warn(message + " Deployment allowed (eddi.vault.grant-enforcement=warn); set it to 'enforce' to block.");
        }
        return result;
    }

    /**
     * Whether {@code agentId} may be deployed.
     *
     * @return {@code false} only in {@code enforce} mode with a provable violation;
     *         {@code warn} logs and allows, {@code off} does not even check
     */
    public boolean mayDeploy(String agentId, Integer agentVersion) {
        return !checkForDeployment(agentId, agentVersion).blocked();
    }

    /**
     * The grant metadata behind one reference, for a preflight to show who the
     * secret is restricted to; {@code null} when it cannot be read.
     */
    public SecretMetadata grantOf(String reference) {
        SecretReference parsed = parseVaultReference(reference);
        return parsed == null || checker == null ? null : checker.metadataOf(parsed);
    }

    /**
     * The reference as a {@link SecretReference}, or {@code null} when it is not a
     * plain vault reference — the checker also reports unreadable connections and
     * references assembled from variables, as prose.
     */
    public static SecretReference parseVaultReference(String reference) {
        if (reference == null || !(reference.startsWith("${vault:") || reference.startsWith("${eddivault:")) || !reference.endsWith("}")) {
            return null;
        }
        try {
            return SecretReference.parse(reference);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The append-grant call that adds {@code agentId} to {@code secret}. */
    static String appendEndpoint(SecretReference secret, String agentId) {
        return "POST /secretstore/secrets/" + secret.tenantId() + "/" + secret.keyName() + "/grant/agents/" + agentId;
    }

    /**
     * The operator-facing explanation. Written for somebody who has never heard of
     * {@code allowedAgents}: it names each secret, says why a NEW agent always hits
     * this, and gives the exact call — the old log line said only "widen the
     * grant".
     */
    private static String describe(String agentId, Integer agentVersion, List<DeploymentFailure.SecretRef> secrets, List<String> endpoints) {
        var names = new StringJoiner(", ");
        for (DeploymentFailure.SecretRef secret : secrets) {
            names.add(secret.keyName() != null ? secret.tenantId() + "/" + secret.keyName() : secret.reference());
        }
        var message = new StringBuilder();
        message.append(String.format("Agent '%s' v%s uses vault secret(s) it is not granted: %s. ", agentId, agentVersion, names));
        message.append("A secret's grant (allowedAgents) lists agent ids, so an agent created after the grant was written is never on it "
                + "and has to be added.");
        if (!endpoints.isEmpty()) {
            message.append(" Fix (eddi-admin): ").append(String.join("; ", endpoints))
                    .append(" (add ?dryRun=true to preview), or add the agent to the key's grant on the Manager's Secrets page, then deploy again.");
        }
        if (endpoints.size() < secrets.size()) {
            message.append(" A reference that is not a plain vault secret (an unreadable connection, or one assembled from global "
                    + "variables) has no single grant to change: fix the connection or the variables, or remove the reference.");
        }
        return message.toString();
    }
}
