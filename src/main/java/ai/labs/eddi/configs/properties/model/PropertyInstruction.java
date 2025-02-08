package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.http.model.HttpCodeValidator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyInstruction extends Property {
    private String fromObjectPath = "";
    private String toObjectPath = "";
    private Boolean convertToObject = false;
    private Boolean override = true;
    private Boolean runOnValidationError = false;
    private HttpCodeValidator httpCodeValidator;
}
