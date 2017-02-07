package ai.labs.permission;

/**
 * @author ginccc
 */
public interface IAuthorization {
    enum Type {
        ADMINISTRATION,
        WRITE,
        READ,
        USE,
        VIEW
    }

    class UnrecognizedAuthorizationTypeException extends Exception {
        public UnrecognizedAuthorizationTypeException(String message) {
            super(message);
        }
    }
}
