package io.sls.core.parser.model;

import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.runtime.DependencyInjector;

import java.util.Arrays;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 26.01.13
 * Time: 01:00
 */
public class Unknown extends Word {
    public Unknown(String value) {
        super(value, Arrays.asList(DependencyInjector.getInstance().getInstance(IExpressionUtilities.class).createExpression("unknown", value)), null);
    }
}
