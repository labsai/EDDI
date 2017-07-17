package ai.labs.memory;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDataFactory {
    IData createData(String key, Object value);

    IData createData(String key, Object value, List<Object> possibleValues);
}
