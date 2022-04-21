package ai.labs.eddi.engine.memory;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDataFactory {
    <T> IData<T> createData(String key, T value);

    <T> IData<T> createData(String key, T value, boolean isPublic);

    <T> IData<T> createData(String key, T value, List<T> possibleValues);
}
