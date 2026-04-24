/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

/**
 * Utility for rethrowing checked exceptions as unchecked without wrapping them.
 * This allows JAX-RS ExceptionMappers to handle checked exceptions thrown from
 * methods that don't declare them in their throws clause.
 *
 * @author ginccc
 */
public final class SneakyThrow {

    private SneakyThrow() {
    }

    /**
     * Rethrows any exception as unchecked without wrapping it. The ExceptionMapper
     * infrastructure will match the original exception type.
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
