package io.sls.core.runtime.client.packages;

import io.sls.core.runtime.IExecutablePackage;
import io.sls.core.runtime.service.ServiceException;

/**
 * Created by jariscgr on 09.08.2016.
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
