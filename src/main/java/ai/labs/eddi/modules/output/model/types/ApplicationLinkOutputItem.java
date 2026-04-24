/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;

public class ApplicationLinkOutputItem extends OutputItem {
    private String path;
    private String label;
    private int delay;

    public ApplicationLinkOutputItem() {
        initType();
    }

    public ApplicationLinkOutputItem(String path) {
        initType();
        this.path = path;
    }

    @Override
    protected void initType() {
        super.type = "applicationLink";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ApplicationLinkOutputItem that = (ApplicationLinkOutputItem) o;
        return Objects.equals(path, that.path) && Objects.equals(label, that.label) && delay == that.delay;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, label, delay);
    }

    @Override
    public String toString() {
        return path + ";" + label + ";" + delay;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }
}
