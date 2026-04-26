/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

public class LifecycleUtilities {
    public static String createComponentKey(String workflowId, Integer workflowVersion, Integer stepIndex) {
        return workflowId + ":" + workflowVersion + ":" + stepIndex;
    }
}
