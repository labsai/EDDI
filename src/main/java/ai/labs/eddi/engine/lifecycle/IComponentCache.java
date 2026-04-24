/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

public interface IComponentCache {
    Map<String, Object> getComponentMap(String type);

    void put(String type, String key, Object component);
}
