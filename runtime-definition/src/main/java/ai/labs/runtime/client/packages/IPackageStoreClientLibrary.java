package ai.labs.runtime.client.packages;

import ai.labs.exception.ServiceException;
import ai.labs.runtime.IExecutablePackage;

/**
 * @author ginccc
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
