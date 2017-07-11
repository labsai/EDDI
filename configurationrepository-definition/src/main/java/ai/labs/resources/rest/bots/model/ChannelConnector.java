package ai.labs.resources.rest.bots.model;

import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.Map;

/**
 * @author ginccc
 */
@Getter
@Setter
public class ChannelConnector {
    private URI type;
    private Map<String, String> config;
}
