package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPipelineFactory {
    IExecutablePipeline getExecutablePipeline(String packageId, Integer version) throws ServiceException;
}
