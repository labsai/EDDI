package ai.labs.eddi.configs.bots.model;



import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class BotConfiguration {
    private List<URI> packages = new ArrayList<>();
    private List<ChannelConnector> channels = new ArrayList<>();

    public static class ChannelConnector {
        private URI type;
        private Map<String, String> config = new HashMap<>();

        public URI getType() {
            return type;
        }

        public void setType(URI type) {
            this.type = type;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public void setConfig(Map<String, String> config) {
            this.config = config;
        }
    }


    public List<URI> getPackages() {
        return packages;
    }

    public void setPackages(List<URI> packages) {
        this.packages = packages;
    }

    public List<ChannelConnector> getChannels() {
        return channels;
    }

    public void setChannels(List<ChannelConnector> channels) {
        this.channels = channels;
    }
}
