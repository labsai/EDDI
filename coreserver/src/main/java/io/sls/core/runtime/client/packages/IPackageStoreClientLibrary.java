package io.sls.core.runtime.client.packages;

import io.sls.core.runtime.IExecutablePackage;
import io.sls.core.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
