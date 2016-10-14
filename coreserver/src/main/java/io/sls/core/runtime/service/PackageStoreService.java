package io.sls.core.runtime.service;

import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.IRestPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * User: jarisch
 * Date: 17.05.12
 * Time: 20:35
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
