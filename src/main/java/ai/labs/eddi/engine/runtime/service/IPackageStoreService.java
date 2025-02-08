package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;

/**
 * @author ginccc
 */
public interface IPackageStoreService {
    PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException;

    DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException;
}
