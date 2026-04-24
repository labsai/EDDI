/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

/**
 * @author ginccc
 */
public class FoundUnknown extends FoundWord {
    public FoundUnknown(Unknown unknown) {
        super(unknown, false, 0.0);
    }
}
