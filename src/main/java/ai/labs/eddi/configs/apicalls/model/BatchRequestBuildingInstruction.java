/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

public class BatchRequestBuildingInstruction extends BuildingInstruction {
    private Boolean executeCallsSequentially;

    /**
     * Most requests this batch may expand into. Unset or {@code <= 0} means the
     * engine default; a larger value is clamped to the engine's hard ceiling. A
     * batch whose target array is longer than this is refused as a whole — no
     * request is sent — rather than silently truncated.
     */
    private Integer maxBatchSize;

    public Boolean getExecuteCallsSequentially() {
        return executeCallsSequentially;
    }

    public void setExecuteCallsSequentially(Boolean executeCallsSequentially) {
        this.executeCallsSequentially = executeCallsSequentially;
    }

    public Integer getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(Integer maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
