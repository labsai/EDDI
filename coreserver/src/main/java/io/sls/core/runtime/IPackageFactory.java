package io.sls.core.runtime;

import io.sls.core.runtime.service.ServiceException;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 19:21
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
