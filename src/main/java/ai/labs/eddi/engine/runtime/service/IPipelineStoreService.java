package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

/**
 * @author ginccc
 */
public interface IPipelineStoreService {
    PipelineConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException;

    DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException;
}
