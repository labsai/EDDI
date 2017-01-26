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
    private String configurationServerURI;

    @Inject
    public PackageStoreService(IRestInterfaceFactory restInterfaceFactory,
                               @Named("system.configurationServerURI") String configurationServerURI) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.configurationServerURI = configurationServerURI;
    }

    @Override
    public PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException {
        try {
            IRestPackageStore serviceProxy = restInterfaceFactory.get(IRestPackageStore.class, configurationServerURI);
            return serviceProxy.readPackage(packageId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException {
        try {
            IRestDocumentDescriptorStore serviceProxy = restInterfaceFactory.get(IRestDocumentDescriptorStore.class, configurationServerURI);
            return serviceProxy.readDescriptor(packageId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
