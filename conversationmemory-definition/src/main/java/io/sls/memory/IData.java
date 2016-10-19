package io.sls.memory;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * User: jarisch
 * Date: 07.02.12
 * Time: 19:34
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
