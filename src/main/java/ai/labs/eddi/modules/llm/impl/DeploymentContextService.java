package ai.labs.eddi.modules.llm.impl;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Provides deployment environment context for auto-counterweight behavior.
 * <p>
 * When an agent does not have an explicit counterweight level configured, the
 * deployment environment can automatically apply one. In production
 * environments, a "cautious" counterweight is applied by default to reduce risk
 * of unintended actions.
 * <p>
 * Configure via application properties:
 *
 * <pre>
 * eddi.deployment.env=production   # triggers auto-cautious
 * eddi.deployment.env=development  # no auto-counterweight
 * </pre>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class DeploymentContextService {

    private static final Logger LOGGER = Logger.getLogger(DeploymentContextService.class);

    private final String environment;

    public DeploymentContextService(
            @ConfigProperty(name = "eddi.deployment.env", defaultValue = "development") String environment) {
        this.environment = environment;
        LOGGER.infof("DeploymentContextService initialized: env=%s", environment);
    }

    /**
     * @return the configured deployment environment (e.g., "production", "staging",
     *         "development")
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * Returns the counterweight level that should be auto-applied based on the
     * deployment environment, or {@code null} if no auto-counterweight applies.
     * <p>
     * Current mapping:
     * <ul>
     * <li>{@code production} → {@code "cautious"}</li>
     * <li>{@code staging} → {@code "cautious"}</li>
     * <li>all others → {@code null} (no auto-counterweight)</li>
     * </ul>
     *
     * @return the auto-counterweight level, or null
     */
    public String getAutoCounterweightLevel() {
        return switch (environment.toLowerCase()) {
            case "production", "staging" -> "cautious";
            default -> null;
        };
    }
}
