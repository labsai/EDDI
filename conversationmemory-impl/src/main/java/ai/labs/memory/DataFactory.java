package ai.labs.memory;

import java.util.List;

/**
 * @author ginccc
 */
public class DataFactory implements IDataFactory {
    @Override
    public IData createData(String key, Object value) {
        return new Data(key, value);
    }

    @Override
    public IData createData(String key, Object value, List<Object> possibleValues) {
        return new Data(key, value, possibleValues);
    }
}
