package ai.labs.eddi.engine.runtime;

/**
 * @author ginccc
 */
public interface IBotDeploymentManagement {
    void autoDeployBots() throws AutoDeploymentException;

    class AutoDeploymentException extends Exception {
        public AutoDeploymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
