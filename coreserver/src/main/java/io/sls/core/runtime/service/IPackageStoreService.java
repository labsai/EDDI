package io.sls.core.runtime.service;

import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.model.PackageConfiguration;

/**
 * Created by jariscgr on 09.08.2016.
 */
public interface IPackageStoreService {
    PackageConfiguration getKnowledgePackage(String packageId, Integer packageVersion) throws ServiceException;

    DocumentDescriptor getPackageDocumentDescriptor(String packageId, Integer packageVersion) throws ServiceException;
}
