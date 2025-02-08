package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageStoreService implements IPackageStoreService {
    private final IRestPackageStore restPackageStore;
    private final IRestDocumentDescriptorStore restDocumentDescriptorStore;

    @Inject
    public PackageStoreService(IRestPackageStore restPackageStore,
                               IRestDocumentDescriptorStore restDocumentDescriptorStore) {
        this.restPackageStore = restPackageStore;
        this.restDocumentDescriptorStore = restDocumentDescriptorStore;
    }

    @Override
    public PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException {
        try {
            return restPackageStore.readPackage(packageId, packageVersion);
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
