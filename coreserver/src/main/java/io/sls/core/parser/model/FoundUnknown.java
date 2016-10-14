package io.sls.core.parser.model;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 25.01.13
 * Time: 17:26
 */
public class FoundUnknown extends FoundWord {
    public FoundUnknown(Unknown unknown) {
        super(unknown, false, 0.0);
    }
}
