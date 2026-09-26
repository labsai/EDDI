/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    /**
     * Whether this entry's result is already-rendered <em>data</em> rather than an
     * author-written template — e.g. output items an httpcall, LLM or MCP
     * {@code postResponse} built from upstream content. The templating task leaves
     * such entries untouched: rendering them again would evaluate template syntax
     * that arrived inside the data.
     * <p>
     * A per-turn marker: it is not part of the persisted memory snapshot.
     *
     * @return true if the result must not be passed through the template engine
     */
    default boolean isPreRendered() {
        return false;
    }

    /**
     * Marks this entry's result as already-rendered data, see
     * {@link #isPreRendered()}.
     *
     * @param preRendered
     *            true if the result must not be templated again
     */
    default void setPreRendered(boolean preRendered) {
        throw new UnsupportedOperationException("setPreRendered is not supported by " + getClass().getName());
    }
}
