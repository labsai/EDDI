package ai.labs.eddi.secrets;

import org.jboss.logging.Logger;

/**
 * Prints a highly visible startup banner showing the vault status. Called once
 * from {@link ai.labs.eddi.secrets.impl.DatabaseSecretProvider} during
 * {@code @PostConstruct} initialization.
 * <p>
 * The banner makes it unmistakably clear whether secret encryption is active or
 * not, and guides the operator to the documentation for setup instructions.
 * <p>
 * <strong>Security note:</strong> The banner intentionally does NOT print
 * example passphrases or key values. Console output may be captured by screen
 * recordings, screenshots, or CI/CD logs.
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class VaultStartupBanner {

    private static final Logger LOGGER = Logger.getLogger("ai.labs.eddi.VAULT");

    /**
     * Public docs URL for secrets vault configuration. Matches the published docs
     * at docs.labs.ai.
     */
    public static final String DOCS_URL = "https://docs.labs.ai/secrets-vault";

    private VaultStartupBanner() {
        // utility class
    }

    /**
     * Print the vault-enabled banner (master key is configured).
     */
    public static void printEnabled() {
        LOGGER.warn("""

                ╔══════════════════════════════════════════════════════════════════╗
                ║  ✅  SECRETS VAULT: ENABLED                                     ║
                ║                                                                  ║
                ║  Envelope encryption is active (AES-256-GCM).                    ║
                ║  ${eddivault:...} references will be resolved at runtime.        ║
                ║  Audit entries will be HMAC-signed for tamper detection.          ║
                ╚══════════════════════════════════════════════════════════════════╝
                """);
    }

    /**
     * Print the vault-disabled warning (no master key configured). Clearly states
     * the implications and how to fix it.
     */
    public static void printDisabled() {
        LOGGER.warn("""

                ╔══════════════════════════════════════════════════════════════════╗
                ║  ⚠️   SECRETS VAULT: DISABLED — Master key not configured        ║
                ╠══════════════════════════════════════════════════════════════════╣
                ║                                                                  ║
                ║  What this means:                                                ║
                ║    • ${eddivault:...} secret references will NOT be resolved      ║
                ║    • API keys in configs are stored/transmitted as PLAINTEXT      ║
                ║    • Audit log entries will NOT have HMAC integrity signatures    ║
                ║                                                                  ║
                ║  To enable, set the EDDI_VAULT_MASTER_KEY env variable.           ║
                ║  See docs for all configuration options:                          ║
                ║                                                                  ║
                ║  📖  https://docs.labs.ai/secrets-vault                           ║
                ╚══════════════════════════════════════════════════════════════════╝
                """);
    }
}
