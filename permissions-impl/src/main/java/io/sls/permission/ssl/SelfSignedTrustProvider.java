package io.sls.permission.ssl;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactorySpi;
import java.security.*;

/**
 * Provides all secure socket factories, with a socket that ignores
 * problems in the chain of certificate trust. This is good for embedded
 * applications that just want the encryption aspect of SSL communication,
 * without worrying too much about validating the identify of the server at the
 * other end of the connection. In other words, this may leave you vulnerable
 * to a man-in-the-middle attack.
 */

public final class SelfSignedTrustProvider extends Provider {
    /**
     * The name of our algorithm *
     */
    private static final String TRUST_PROVIDER_ALG = "SelfSignedTrustAlgorithm";

    /**
     * Need to refer to ourselves somehow to know if we're already registered *
     */
    private static final String TRUST_PROVIDER_ID = "SelfSignedTrustProvider";

    /**
     * Hook in at the provider level to handle libraries and 3rd party
     * utilities that use their own factory. Requires permission to
     * execute AccessController.doPrivileged,
     * so this probably won't work in applets or other high-security jvms
     */

    public SelfSignedTrustProvider() {
        super(TRUST_PROVIDER_ID,
                0.1,
                "SelfSignedTrustProvider (provides all secure socket factories by ignoring problems in the chain of certificate trust)");

        AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                put("TrustManagerFactory." + SelfSignedTrustManagerFactory.getAlgorithm(),
                        SelfSignedTrustManagerFactory.class.getName());
                return null;
            }
        });
    }

    /**
     * This is the only method the client code need to call. Yup, just put
     * SelfSignedTrustProvider.setAlwaysTrust() into your initialization code
     * and you're good to go
     *
     * @param enableSelfSignedTrustProvider set to true to always trust (set to false
     *                                 it not yet implemented)
     */

    public static void setAlwaysTrust(boolean enableSelfSignedTrustProvider) {
        if (enableSelfSignedTrustProvider) {
            Provider registered = Security.getProvider(TRUST_PROVIDER_ID);
            if (null == registered) {
                Security.insertProviderAt(new SelfSignedTrustProvider(), 1);
                Security.setProperty("ssl.TrustManagerFactory.algorithm",
                        TRUST_PROVIDER_ALG);
            }
        } else {
            throw new UnsupportedOperationException(
                    "Disable SelfSigned trust provider not yet implemented");
        }
    }

    /**
     * The factory for the SelfSignedTrustProvider
     */
    public final static class SelfSignedTrustManagerFactory
            extends TrustManagerFactorySpi {
        public SelfSignedTrustManagerFactory() {
        }

        protected void engineInit(ManagerFactoryParameters mgrparams) {
        }

        protected void engineInit(KeyStore keystore) {
        }

        /**
         * Returns a collection of trust managers that are SelfSigned.
         * This collection is just a single element array containing
         * our {@link SelfSignedTrustManager} class.
         */
        protected TrustManager[] engineGetTrustManagers() {
            // Returns a new array of just a single SelfSignedTrustManager.
            return new TrustManager[]{new SelfSignedTrustManager()};
        }

        /**
         * Returns our "SelfSignedTrustAlgorithm" string.
         *
         * @return The string, "SelfSignedTrustAlgorithm"
         */
        public static String getAlgorithm() {
            return TRUST_PROVIDER_ALG;
        }
    }
}
