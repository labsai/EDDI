package ai.labs.eddi.engine.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Produces a stable instance identifier for this EDDI process. Format:
 * {@code hostname-xxxx} where xxxx is a short UUID suffix. Used to tag log
 * entries for multi-instance disambiguation.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class InstanceIdProducer {

    private static final Logger log = Logger.getLogger(InstanceIdProducer.class);

    private String instanceId;

    @PostConstruct
    void init() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
            log.debugv("Could not resolve hostname, using 'unknown': {0}", e.getMessage());
        }

        String shortUuid = UUID.randomUUID().toString().substring(0, 4);
        this.instanceId = hostname + "-" + shortUuid;
        log.infov("EDDI instance ID: {0}", instanceId);
    }

    public String getInstanceId() {
        return instanceId;
    }
}
