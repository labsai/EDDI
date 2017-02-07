package ai.labs.memory;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @author ginccc
 */
public interface IData extends Serializable {
    String getKey();

    List getPossibleResults();

    Object getResult();

    Date getTimestamp();

    /**
     * Whether or not this data will be send to the client
     *
     * @return true if it should be included in the client response
     */
    boolean isPublic();

    void setPossibleResults(List possibleResults);

    void setResult(Object result);

    void setPublic(boolean isPublic);
}
