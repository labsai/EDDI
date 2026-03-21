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
