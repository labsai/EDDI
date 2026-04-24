/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class Properties extends HashMap<String, Object> {
    public Properties(Map<? extends String, ?> m) {
        super(m);
    }

    public Properties() {
    }
}
