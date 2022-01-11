package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IData;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * @author ginccc
 */
@Getter
@Setter
public class Data<T> implements IData<T> {
    private final String key;
    private List<T> possibleResults;
    private T result;
    private final Date timestamp;
    private String originPackageId;
    private boolean isPublic;

    public Data(String key, T result) {
        this(key, result, Collections.singletonList(result), new Date(System.currentTimeMillis()));
    }

    public Data(String key, T result, List<T> possibleResults) {
        this(key, result, possibleResults, new Date(System.currentTimeMillis()));
    }

    public Data(String key, T result, List<T> possibleResults, Date timestamp) {
        this(key, result, possibleResults, timestamp, false);
    }

    public Data(String key, T result, List<T> possibleResults, Date timestamp, boolean isPublic) {
        this.key = key;
        this.result = result == null ? chooseRandomResult(possibleResults) : result;
        this.possibleResults = possibleResults;
        this.timestamp = timestamp;
        this.isPublic = isPublic;
    }

    private T chooseRandomResult(List<T> results) {
        if (!results.isEmpty()) {
            Random random = new Random();
            int randNumber = random.nextInt(results.size());
            return results.get(randNumber);
        }

        return null;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof IData && key.equals(((Data) o).key);
    }

    @Override
    public int hashCode() {
        return key != null ? key.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "result" +
                "{key='" + key + '\'' +
                ", result=" + result +
                '}';
    }
}
