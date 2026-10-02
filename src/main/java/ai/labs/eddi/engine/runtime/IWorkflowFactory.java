/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IWorkflowFactory {
    IExecutableWorkflow getExecutableWorkflow(String workflowId, Integer version) throws ServiceException;

    /**
     * Forgets a deleted workflow version ({@code null}: every version) so it is
     * neither served nor kept in memory. Default: nothing cached, nothing to do.
     */
    default void evict(String workflowId, Integer workflowVersion) {
    }
}
