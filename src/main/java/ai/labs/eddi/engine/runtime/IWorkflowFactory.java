package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IWorkflowFactory {
    IExecutableWorkflow getExecutableWorkflow(String workflowId, Integer version) throws ServiceException;
}
