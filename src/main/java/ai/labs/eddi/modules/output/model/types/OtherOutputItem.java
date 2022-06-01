package ai.labs.eddi.modules.output.model.types;

import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;

import java.util.LinkedHashMap;

@JsonSchemaInject(json = "{\"additionalProperties\" : true}")
public class OtherOutputItem extends LinkedHashMap<String, String> {
}
