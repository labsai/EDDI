package ai.labs.runtime.client.packages;

import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
