package io.sls.core.service.restinterfaces;

/**
 * Created by jariscgr on 22.08.2016.
 */
public interface IRestInterfaceFactory {
    <T> T get(Class<T> clazz, String targetServerUri) throws RestInterfaceFactory.RestInterfaceFactoryException;

    <T> T get(Class<T> clazz, String targetServerUri, String securityToken) throws RestInterfaceFactory.RestInterfaceFactoryException;
}
