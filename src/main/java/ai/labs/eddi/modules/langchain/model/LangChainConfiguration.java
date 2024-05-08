package ai.labs.eddi.modules.langchain.model;

import java.util.List;
import java.util.Map;

public record LangChainConfiguration(List<Task> tasks) {
    public record Task(List<String> actions,
                       String id,
                       String type,
                       String description,
                       Map<String, String> parameters) {
        }
}
