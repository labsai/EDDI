/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.workflows;

import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IWorkflowStoreClientLibrary {
    IExecutableWorkflow getExecutableWorkflow(String workflowId, Integer workflowVersion) throws ServiceException;
}
