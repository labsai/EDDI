package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.pipelines.IRestPipelineStore;
import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PipelineStoreService implements IPipelineStoreService {
    private final IRestPipelineStore restPipelineStore;
    private final IRestDocumentDescriptorStore restDocumentDescriptorStore;

    @Inject
    public PipelineStoreService(IRestPipelineStore restPipelineStore,
                               IRestDocumentDescriptorStore restDocumentDescriptorStore) {
        this.restPipelineStore = restPipelineStore;
        this.restDocumentDescriptorStore = restDocumentDescriptorStore;
    }

    @Override
    public PipelineConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException {
        try {
            return restPipelineStore.readPackage(packageId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException {
        try {
            return restDocumentDescriptorStore.readDescriptor(packageId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
