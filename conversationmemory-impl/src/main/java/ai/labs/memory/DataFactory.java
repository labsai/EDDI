package ai.labs.memory;

import java.util.List;

/**
 * @author ginccc
 */
public class DataFactory implements IDataFactory {
    @Override
    public <T> IData<T> createData(String key, T value) {
        return new Data<>(key, value);
    }

    @Override
    public <T> IData<T> createData(String key, T value, List<T> possibleValues) {
        return new Data<>(key, value, possibleValues);
    }
}
