package io.sls.core.runtime;

import io.sls.core.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
