package io.sls.permission;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 10:58
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
