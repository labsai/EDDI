package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HttpPostResponse extends PostResponse {
    private RetryHttpCallInstruction retryHttpCallInstruction;
}
