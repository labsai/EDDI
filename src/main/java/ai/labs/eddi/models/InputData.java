package ai.labs.eddi.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class InputData {
    private String input = "";
    private Map<String, Context> context = new HashMap<>();
}
