package ai.labs.runtime.service;

import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.IRestPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * @author ginccc
 */
public class PackageStoreService implements IPackageStoreService {
    private final IRestInterfaceFactory restInterfaceFactory;
    private String apiServerURI;

    @Inject
    public PackageStoreService(IRestInterfaceFactory restInterfaceFactory,
                               @Named("system.apiServerURI") String apiServerURI) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.apiServerURI = apiServerURI;
    }

    @Override
    public PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException {
        try {
            IRestPackageStore serviceProxy = restInterfaceFactory.get(IRestPackageStore.class, apiServerURI);
            return (PackageConfiguration) serviceProxy.readPackage(packageId, packageVersion).getEntity();
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException {
        try {
            IRestDocumentDescriptorStore serviceProxy = restInterfaceFactory.get(IRestDocumentDescriptorStore.class, apiServerURI);
            return serviceProxy.readDescriptor(packageId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
