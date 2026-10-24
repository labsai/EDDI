/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

/**
 * Runs one http call once per element of an array and collects every element's
 * result — status, body or error — into one array, stored like a normal
 * response under the call's {@code responseObjectName}.
 * <p>
 * Not {@code preRequest.batchRequests}: that one only works with
 * {@code fireAndForget} and discards every response, and a stored config
 * carrying it on a call that waits for its response must keep sending exactly
 * one request. {@code forEach} is the collecting form, and is refused together
 * with either of them.
 * <p>
 * Every field is read, never written back: the instance is part of the cached
 * configuration that every conversation of the agent shares.
 */
public class ForEachInstruction {

    /** {@link #onItemFailure}: record each failed item and carry on. */
    public static final String ON_ITEM_FAILURE_CONTINUE = "continue";

    /**
     * {@link #onItemFailure}: once every item has finished, fail the step when any
     * item failed. The results are stored first, so the work already paid for is
     * not lost.
     */
    public static final String ON_ITEM_FAILURE_FAIL = "fail";

    /** Default {@link #maxConcurrency}: one item at a time. */
    public static final int DEFAULT_MAX_CONCURRENCY = 1;

    /**
     * Hard ceiling for {@link #maxConcurrency}. A save above it is refused; a
     * stored value above it runs at it.
     */
    public static final int MAX_CONCURRENCY_CEILING = 16;

    /**
     * Plain dot path to the array in the template data, e.g.
     * {@code properties.orders} or {@code ordersApi.items} — the same notation as a
     * property instruction's {@code fromObjectPath}, not a template. A path that
     * resolves to nothing is an empty loop; one that resolves to something other
     * than an array fails the call.
     */
    private String pathToTargetArray;

    /**
     * Name the current element is bound to in the request's template data, so
     * {@code {item.id}} renders the element's {@code id}. The element itself is
     * bound, not a string rendering of it.
     */
    private String iterationObjectName = "item";

    /**
     * Optional Qute condition, evaluated per element with the element bound under
     * {@link #iterationObjectName}; elements for which it is false are skipped.
     * Author-written, like every other template of the call.
     */
    private String templateFilterExpression;

    /**
     * Most elements one execution may process. Unset or {@code <= 0} uses the
     * deployment's batch default ({@code eddi.httpcalls.batch.default-max-size});
     * the batch ceiling ({@code eddi.httpcalls.batch.max-size-ceiling}) caps it.
     * More elements than this refuses the call as a whole before anything is sent,
     * rather than silently dropping the tail.
     */
    private Integer maxItems;

    /**
     * How many items may be in flight at once, 1 to
     * {@value #MAX_CONCURRENCY_CEILING}. Default {@value #DEFAULT_MAX_CONCURRENCY}:
     * sequential, so a config that only wanted the loop sends exactly the load the
     * same calls written one after another would, and a rate-limited or billed API
     * is not hit in parallel unless the author asked for it.
     */
    private Integer maxConcurrency;

    /**
     * {@value #ON_ITEM_FAILURE_CONTINUE} (default) or
     * {@value #ON_ITEM_FAILURE_FAIL}. A failing item never cancels its siblings
     * either way.
     */
    private String onItemFailure;

    /**
     * Budget for every item's body together, in characters of their stored form.
     * Items are kept in order until the budget is spent; a later item's body is
     * then dropped (its status is kept) and the item says so. Unset or {@code <= 0}
     * uses the call's {@code maxResponseSizeInBytes} — the array as a whole gets
     * what one response would.
     */
    private Integer maxTotalResponseSizeInBytes;

    public String getPathToTargetArray() {
        return pathToTargetArray;
    }

    public void setPathToTargetArray(String pathToTargetArray) {
        this.pathToTargetArray = pathToTargetArray;
    }

    public String getIterationObjectName() {
        return iterationObjectName;
    }

    public void setIterationObjectName(String iterationObjectName) {
        this.iterationObjectName = iterationObjectName;
    }

    public String getTemplateFilterExpression() {
        return templateFilterExpression;
    }

    public void setTemplateFilterExpression(String templateFilterExpression) {
        this.templateFilterExpression = templateFilterExpression;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(Integer maxItems) {
        this.maxItems = maxItems;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getOnItemFailure() {
        return onItemFailure;
    }

    public void setOnItemFailure(String onItemFailure) {
        this.onItemFailure = onItemFailure;
    }

    public Integer getMaxTotalResponseSizeInBytes() {
        return maxTotalResponseSizeInBytes;
    }

    public void setMaxTotalResponseSizeInBytes(Integer maxTotalResponseSizeInBytes) {
        this.maxTotalResponseSizeInBytes = maxTotalResponseSizeInBytes;
    }

    /**
     * The concurrency in force: the default for unset or non-positive, capped at
     * {@link #MAX_CONCURRENCY_CEILING}.
     */
    public int effectiveMaxConcurrency() {
        int configured = maxConcurrency == null || maxConcurrency <= 0 ? DEFAULT_MAX_CONCURRENCY : maxConcurrency;
        return Math.min(configured, MAX_CONCURRENCY_CEILING);
    }

    /**
     * Whether a failed item fails the step. An unknown value (a stored config that
     * predates the save-time check) fails it: a policy that cannot be read should
     * surface, not pass silently.
     */
    public boolean failsStepOnItemFailure() {
        if (onItemFailure == null || onItemFailure.isBlank()) {
            return false;
        }
        return !ON_ITEM_FAILURE_CONTINUE.equalsIgnoreCase(onItemFailure.trim());
    }

    /** Whether {@code value} is a policy {@link #onItemFailure} accepts. */
    public static boolean isKnownOnItemFailure(String value) {
        return value == null || value.isBlank() || ON_ITEM_FAILURE_CONTINUE.equalsIgnoreCase(value.trim())
                || ON_ITEM_FAILURE_FAIL.equalsIgnoreCase(value.trim());
    }
}
