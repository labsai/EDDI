package ai.labs.rest.restinterfaces;

/**
 * @author ginccc
 */
public interface IRestInterfaceFactory {
    <T> T get(Class<T> clazz) throws RestInterfaceFactory.RestInterfaceFactoryException;
}
