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

    String getOriginWorkflowId();

    void setOriginWorkflowId(String workflowId);

    /**
     * Whether or not this data will be send to the client
     *
     * @return true if it should be included in the client response
     */
    boolean isPublic();

    void setPossibleResults(List<T> possibleResults);

    void setResult(T result);

    void setPublic(boolean isPublic);

    /**
     * Whether this data entry was committed (task succeeded) or uncommitted (task
     * failed). Uncommitted data is excluded from LLM prompt assembly in subsequent
     * turns but remains in memory for debugging and audit.
     * <p>
     * Default: {@code true} (backwards-compatible — all existing data is
     * committed).
     *
     * @return true if the data is committed
     * @since 6.0.0
     */
    boolean isCommitted();

    /**
     * Mark this data entry as committed or uncommitted.
     *
     * @param committed
     *            true to mark as committed, false for uncommitted
     * @since 6.0.0
     */
    void setCommitted(boolean committed);
}
