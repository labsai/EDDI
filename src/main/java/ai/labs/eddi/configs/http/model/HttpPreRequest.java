package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HttpPreRequest extends PreRequest {
    private BatchRequestBuildingInstruction batchRequests;
    private Integer delayBeforeExecutingInMillis = 0;
}
