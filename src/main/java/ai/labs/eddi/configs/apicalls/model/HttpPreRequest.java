/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

public class HttpPreRequest extends PreRequest {
    private BatchRequestBuildingInstruction batchRequests;
    private Integer delayBeforeExecutingInMillis = 0;

    public BatchRequestBuildingInstruction getBatchRequests() {
        return batchRequests;
    }

    public void setBatchRequests(BatchRequestBuildingInstruction batchRequests) {
        this.batchRequests = batchRequests;
    }

    public Integer getDelayBeforeExecutingInMillis() {
        return delayBeforeExecutingInMillis;
    }

    public void setDelayBeforeExecutingInMillis(Integer delayBeforeExecutingInMillis) {
        this.delayBeforeExecutingInMillis = delayBeforeExecutingInMillis;
    }
}
