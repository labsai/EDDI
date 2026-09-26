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
     * Whether the value of this entry must be delivered verbatim — never rendered
     * as a template by the templating task ({@code OutputTemplateTask}).
     * <p>
     * {@code true} for data that was supplied from outside the configuration rather
     * than authored in it: output and quick replies sent as {@code context} on a
     * turn, and output built from an HTTP response by {@code postResponse} (which
     * has already been rendered once, with the response substituted in). Those
     * values are data. Rendering them again would let whoever controls them write
     * Qute: read {@code {snippets.*}} and {@code {vars.*}}, or loop and allocate
     * until the worker stalls.
     * <p>
     * The templating task also sets it on every entry it has rendered, so a second
     * templating pass in the same turn (one per workflow) cannot evaluate what the
     * first one substituted in.
     * <p>
     * <b>Persisted</b> with the step ({@code ResultSnapshot#isVerbatim}): a
     * tool-call HITL resume reloads memory and re-enters the pipeline after the
     * output task, so a flag that did not survive the reload would let a later
     * templating task render the entry.
     * <p>
     * Default: {@code false}. Phrased this way round so that the safe answer for
     * every existing entry — authored output, templated as before — is also the
     * default of a Mockito mock.
     *
     * @return true if the value must not be templated
     */
    boolean isVerbatim();

    /**
     * Marks this entry as verbatim (or not). See {@link #isVerbatim()}.
     *
     * @param verbatim
     *            true for data supplied from outside the configuration
     */
    void setVerbatim(boolean verbatim);
}
