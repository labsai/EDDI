package io.sls.runtime;

import io.sls.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
