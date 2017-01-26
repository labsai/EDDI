package ai.labs.runtime;

import ai.labs.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
