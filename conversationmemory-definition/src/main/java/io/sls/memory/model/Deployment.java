package io.sls.memory.model;

/**
 * User: jarisch
 * Date: 04.10.12
 * Time: 16:22
 */
public class Deployment {
    public enum Environment {
        restricted,
        unrestricted,
        test
    }

    public enum Status {
        READY,
        IN_PROGRESS,
        NOT_FOUND,
        ERROR
    }
}
