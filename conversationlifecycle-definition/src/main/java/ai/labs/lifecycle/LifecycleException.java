package ai.labs.lifecycle;

/**
 * @author ginccc
 */
public class LifecycleException extends Exception {
    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Exception exception) {
        super(message, exception);
    }

    public static class LifecycleInterruptedException extends LifecycleException {

        LifecycleInterruptedException(String message) {
            super(message);
        }

        public LifecycleInterruptedException(String message, Exception e) {
            super(message);
        }
    }
}
