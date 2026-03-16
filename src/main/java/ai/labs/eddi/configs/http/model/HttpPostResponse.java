package ai.labs.eddi.configs.http.model;


public class HttpPostResponse extends PostResponse {
    private RetryHttpCallInstruction retryHttpCallInstruction;

    public RetryHttpCallInstruction getRetryHttpCallInstruction() {
        return retryHttpCallInstruction;
    }

    public void setRetryHttpCallInstruction(RetryHttpCallInstruction retryHttpCallInstruction) {
        this.retryHttpCallInstruction = retryHttpCallInstruction;
    }
}
