package ai.labs.eddi.configs.bots.model;


import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@Getter
@Setter
public class BotConfiguration {
    private List<URI> packages = new ArrayList<>();
    private List<ChannelConnector> channels = new ArrayList<>();

    @Getter
    @Setter
    public static class ChannelConnector {
        private URI type;
        private Map<String, String> config = new HashMap<>();
    }

}
