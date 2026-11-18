/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.runtime.client.workflows.WorkflowStoreClientLibrary.WorkflowInitializationException;

import java.util.List;

/**
 * Turns the exception a failed deployment ended with into the
 * {@link DeploymentFailure} the API returns: a reason <em>code</em> from a
 * closed set and a fixed message per code.
 * <p>
 * The message never contains the exception's own text. That text can hold a
 * configuration value, a connection string, a host name from a config or an
 * internal path, and the failure reaches every caller who may use the agent.
 * The cause is in the server log; the code says what kind of thing went wrong
 * and what to do about it.
 */
public final class DeploymentFailures {

    /** A step of a workflow names an extension this server does not have. */
    public static final String STEP_TYPE_UNKNOWN = "STEP_TYPE_UNKNOWN";

    /** A configuration the agent uses is invalid (it refused to configure). */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";

    /** A configuration or workflow the agent references does not exist. */
    public static final String REFERENCE_MISSING = "REFERENCE_MISSING";

    /** Every code a failed deployment can carry, for the metric's tag values. */
    public static final List<String> CODES = List.of(DeploymentFailure.VAULT_GRANT_MISSING, STEP_TYPE_UNKNOWN, CONFIG_INVALID,
            REFERENCE_MISSING, DeploymentFailure.DEPLOYMENT_FAILED);

    private static final int MAX_CAUSE_DEPTH = 8;

    private DeploymentFailures() {
    }

    /** The reason code of a deployment that failed with {@code cause}. */
    public static String codeOf(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof UnrecognizedExtensionException) {
                return STEP_TYPE_UNKNOWN;
            }
            if (current instanceof WorkflowConfigurationException || current instanceof IllegalExtensionConfigurationException) {
                return CONFIG_INVALID;
            }
            if (current instanceof IResourceStore.ResourceNotFoundException) {
                return REFERENCE_MISSING;
            }
            if (current instanceof WorkflowInitializationException && current.getCause() == null) {
                // No deeper cause to classify: an initialisation failure is a bad
                // configuration far more often than a server fault.
                return CONFIG_INVALID;
            }
        }
        return DeploymentFailure.DEPLOYMENT_FAILED;
    }

    /** The failure to record for a deployment that ended with {@code cause}. */
    public static DeploymentFailure failureOf(Throwable cause, String agentId, Integer version) {
        String code = codeOf(cause);
        String subject = "Agent '" + agentId + "' v" + version;
        String message = switch (code) {
            case STEP_TYPE_UNKNOWN -> subject + " could not be deployed: a workflow names a step this server does not have. "
                    + "Check the step types of its workflows.";
            case CONFIG_INVALID -> subject + " could not be deployed: one of its configurations is invalid. "
                    + "Check the server log for the field.";
            case REFERENCE_MISSING -> subject + " could not be deployed: a workflow or configuration it references does not exist "
                    + "(was it deleted?).";
            default -> AgentFactory.failureMessage(agentId, version);
        };
        return new DeploymentFailure(code, message, null, null);
    }
}
