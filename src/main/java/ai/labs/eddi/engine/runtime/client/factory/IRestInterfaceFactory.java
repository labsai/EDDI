package ai.labs.eddi.engine.runtime.client.factory;

/**
 * @author ginccc
 */
public interface IRestInterfaceFactory {
    <T> T get(Class<T> clazz) throws RestInterfaceFactory.RestInterfaceFactoryException;

    <T> T get(Class<T> clazz, String serverUrl);
}
