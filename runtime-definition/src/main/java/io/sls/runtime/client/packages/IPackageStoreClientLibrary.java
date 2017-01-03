package io.sls.runtime.client.packages;

import io.sls.runtime.IExecutablePackage;
import io.sls.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
