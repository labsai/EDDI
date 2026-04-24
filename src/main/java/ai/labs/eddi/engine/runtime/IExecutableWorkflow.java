/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutableWorkflow {
    String getName();

    String getDescription();

    String getWorkflowId();

    ILifecycleManager getLifecycleManager();
}
