package ai.labs.runtime;

import ai.labs.exception.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
