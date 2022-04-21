package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IPackageFactory {
    IExecutablePackage getExecutablePackage(String packageId, Integer version) throws ServiceException;
}
