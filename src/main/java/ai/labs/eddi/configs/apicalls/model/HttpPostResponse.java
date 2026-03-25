package ai.labs.eddi.configs.apicalls.model;

public class HttpPostResponse extends PostResponse {
    private RetryApiCallInstruction retryApiCallInstruction;

    public RetryApiCallInstruction getRetryApiCallInstruction() {
        return retryApiCallInstruction;
    }

    public void setRetryApiCallInstruction(RetryApiCallInstruction retryApiCallInstruction) {
        this.retryApiCallInstruction = retryApiCallInstruction;
    }
}
