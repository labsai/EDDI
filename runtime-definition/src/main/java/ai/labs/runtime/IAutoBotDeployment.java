package ai.labs.runtime;

/**
 * @author ginccc
 */
public interface IAutoBotDeployment {
    void autoDeployBots() throws AutoDeploymentException;


    class AutoDeploymentException extends Exception {
        public AutoDeploymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
