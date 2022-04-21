package ai.labs.eddi.engine.memory;

import java.util.Date;
import java.util.List;

/**
 * @author ginccc
 */
public interface IData<T> {
    String getKey();

    List<T> getPossibleResults();

    T getResult();

    Date getTimestamp();

    String getOriginPackageId();

    void setOriginPackageId(String packageId);

    /**
     * Whether or not this data will be send to the client
     *
     * @return true if it should be included in the client response
     */
    boolean isPublic();

    void setPossibleResults(List<T> possibleResults);

    void setResult(T result);

    void setPublic(boolean isPublic);
}
