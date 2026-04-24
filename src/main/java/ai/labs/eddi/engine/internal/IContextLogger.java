/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.Deployment;

import java.util.Map;

public interface IContextLogger {
    Map<String, String> createLoggingContext(Deployment.Environment environment, String agentId, String conversationId, String userId);

    void setLoggingContext(Map<String, String> loggingContext);

    void clearLoggingContext();
}
