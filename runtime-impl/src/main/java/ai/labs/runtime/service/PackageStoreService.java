package ai.labs.runtime.service;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;

import javax.inject.Inject;

/**
 * @author ginccc
 */
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
