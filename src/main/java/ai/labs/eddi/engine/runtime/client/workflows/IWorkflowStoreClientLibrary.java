package ai.labs.eddi.engine.runtime.client.workflows;

import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IWorkflowStoreClientLibrary {
    IExecutableWorkflow getExecutableWorkflow(String packageId, Integer packageVersion) throws ServiceException;
}
