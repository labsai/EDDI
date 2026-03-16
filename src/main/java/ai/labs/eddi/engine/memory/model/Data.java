package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IData;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * @author ginccc
 */
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

    public final String getKey() {
        return key;
    }

    public void setKey(final String key) {
        this.key = key;
    }

    public List<T> getPossibleResults() {
        return possibleResults;
    }

    public void setPossibleResults(List<T> possibleResults) {
        this.possibleResults = possibleResults;
    }

    public T getResult() {
        return result;
    }

    public void setResult(T result) {
        this.result = result;
    }

    public final Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(final Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getOriginPackageId() {
        return originPackageId;
    }

    public void setOriginPackageId(String originPackageId) {
        this.originPackageId = originPackageId;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }
}
