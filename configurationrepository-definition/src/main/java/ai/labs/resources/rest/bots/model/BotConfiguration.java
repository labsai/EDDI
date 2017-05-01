package ai.labs.resources.rest.bots.model;


import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class BotConfiguration {
    private boolean authenticationRequired;
    private List<URI> packages;
    private List<ChannelConnector> channels;

    public BotConfiguration() {
        authenticationRequired = true;
        this.packages = new ArrayList<>();
        this.channels = new ArrayList<>();
    }
}
