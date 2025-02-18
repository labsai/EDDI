package ai.labs.eddi.modules.langchain.model;

import ai.labs.eddi.configs.http.model.PostResponse;
import ai.labs.eddi.configs.http.model.PreRequest;

import java.util.List;
import java.util.Map;

public record LangChainConfiguration(List<Task> tasks) {
    public record Task(List<String> actions,
                       String id,
                       String type,
                       String description,
                       PreRequest preRequest,
                       Map<String, String> parameters,
                       String responseObjectName,
                       String responseMetadataObjectName,
                       PostResponse postResponse) {
    }
}
