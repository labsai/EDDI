package ai.labs.eddi.engine.runtime.client.packages;

import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageStoreClientLibrary {
    IExecutablePackage getExecutablePackage(String packageId, Integer packageVersion) throws ServiceException;
}
