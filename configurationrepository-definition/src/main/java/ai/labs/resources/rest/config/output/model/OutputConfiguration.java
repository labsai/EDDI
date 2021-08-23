package ai.labs.resources.rest.config.output.model;

import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutputConfiguration {
    private String action;
    private int timesOccurred;
    private List<OutputType> outputs = new LinkedList<>();
    private List<QuickReply> quickReplies = new LinkedList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OutputConfiguration that = (OutputConfiguration) o;

        return action.equals(that.action) && timesOccurred == that.timesOccurred;
    }

    @Override
    public int hashCode() {
        int result = action.hashCode();
        result = 31 * result + timesOccurred;
        return result;
    }

    @Getter
    @Setter
    public static class OutputType {
        private String type;
        @JsonSchemaInject(json =
                "{\"anyOf\": " +
                        "[" +
                        "{" +
                        " \"type\": \"array\"," +
                        " \"items\": {" +
                        "               \"type\": \"string\"" +
                        "            }" +
                        "}," +
                        "{" +
                        " \"type\": \"array\"," +
                        " \"items\": {" +
                        "                \"type\": \"object\"," +
                        "                \"properties\": {" +
                        "                    \"text\":{" +
                        "                        \"type\":\"string\"" +
                        "                    }" +
                        "                }, " +
                        "                \"additionalProperties\":true" +
                        "            }" +
                        "} " +
                        "]}")
        private List<Object> valueAlternatives = new LinkedList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuickReply {
        private String value;
        private String expressions;
        private Boolean isDefault;
    }
}
