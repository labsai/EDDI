/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class InputData {
    private String input = "";
    private Map<String, Context> context = new HashMap<>();

    public InputData() {
    }

    public InputData(String input, Map<String, Context> context) {
        this.input = input;
        this.context = context;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public Map<String, Context> getContext() {
        return context;
    }

    public void setContext(Map<String, Context> context) {
        this.context = context;
    }
}
