/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Admits a call into a {@link BackupConcurrencyLimited} bean only while
 * {@link BackupConcurrencyGate} has a slot free. Runs after the security
 * interceptors, so a caller without the role is refused for that, not for
 * capacity.
 */
@BackupConcurrencyLimited
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class BackupConcurrencyInterceptor {

    @Inject
    BackupConcurrencyGate gate;

    @AroundInvoke
    Object limit(InvocationContext context) throws Exception {
        return gate.run(context::proceed);
    }
}
