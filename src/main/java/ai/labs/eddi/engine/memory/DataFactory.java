package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.Data;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
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
