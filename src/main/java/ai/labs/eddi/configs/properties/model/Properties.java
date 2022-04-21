package ai.labs.eddi.configs.properties.model;

import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class Properties extends HashMap<String, Object> {
    public Properties(Map<? extends String, ?> m) {
        super(m);
    }
}
