package ai.labs.eddi.engine.runtime.client.pipelines;

import ai.labs.eddi.engine.runtime.IExecutablePipeline;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPipelineStoreClientLibrary {
    IExecutablePipeline getExecutablePipeline(String packageId, Integer packageVersion) throws ServiceException;
}
