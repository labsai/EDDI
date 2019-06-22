package ai.labs.runtime.service;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;

/**
 * @author ginccc
 */
public interface IPackageStoreService {
    PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException;

    DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException;
}
