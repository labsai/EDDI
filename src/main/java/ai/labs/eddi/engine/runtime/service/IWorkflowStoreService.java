/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

/**
 * @author ginccc
 */
public interface IWorkflowStoreService {
    WorkflowConfiguration getKnowledgeWorkflow(String workflowId, Integer workflowVersion) throws ServiceException;

    DocumentDescriptor getWorkflowDocumentDescriptor(String workflowId, Integer workflowVersion) throws ServiceException;
}
