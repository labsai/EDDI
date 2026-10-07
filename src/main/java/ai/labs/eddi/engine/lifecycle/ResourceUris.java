/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;

import java.net.URI;

/**
 * Reads the {@code uri} of a workflow step's {@code config} for a task's
 * {@code configure()}.
 * <p>
 * A missing or malformed {@code uri} used to surface as a
 * {@code NullPointerException} or an {@code IllegalArgumentException} from
 * {@code URI.create} — the deployment failed with a stack trace that named no
 * step. It is a {@link WorkflowConfigurationException} naming the step type
 * now, the same failure every other configuration error produces.
 */
public final class ResourceUris {

    private ResourceUris() {
    }

    /**
     * @param uriObj
     *            the raw {@code config.uri} value
     * @param stepType
     *            the step type, for the message (e.g. {@code ai.labs.behavior})
     * @return the parsed URI
     * @throws WorkflowConfigurationException
     *             when the value is missing, blank or not a valid URI
     */
    public static URI require(Object uriObj, String stepType) throws WorkflowConfigurationException {
        if (uriObj == null || uriObj.toString().isBlank()) {
            throw new WorkflowConfigurationException("No resource URI ('config.uri') has been defined for the " + stepType + " step!");
        }
        try {
            return URI.create(uriObj.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new WorkflowConfigurationException("The " + stepType + " step's 'config.uri' is not a valid URI: " + uriObj, e);
        }
    }
}
