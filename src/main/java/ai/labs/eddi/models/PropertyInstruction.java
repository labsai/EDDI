package ai.labs.eddi.models;

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
    private Boolean override = true;
    private Boolean runOnValidationError = false;
    private HttpCodeValidator httpCodeValidator;
}
