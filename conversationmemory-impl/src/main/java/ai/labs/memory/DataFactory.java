package ai.labs.memory;

import ai.labs.memory.model.Data;

import java.util.List;

/**
 * @author ginccc
 */
public class DataFactory implements IDataFactory {
    @Override
    public <T> IData<T> createData(String key, T value) {
        return createData(key, value, false);
    }

    @Override
    public <T> IData<T> createData(String key, T value, boolean isPublic) {
        Data<T> data = new Data<>(key, value);
        data.setPublic(isPublic);
        return data;
    }

    @Override
    public <T> IData<T> createData(String key, T value, List<T> possibleValues) {
        return new Data<>(key, value, possibleValues);
    }
}
