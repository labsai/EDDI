package ai.labs.channels.config.model;

import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class ChannelDefinition {
    private String name;
    private URI type;
    private boolean active = true;
    private Map<String, Object> config = new HashMap<>();
}
